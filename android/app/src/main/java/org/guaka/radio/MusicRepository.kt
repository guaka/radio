package org.guaka.radio

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class RadioApp : Application() {
    val repository by lazy { MusicRepository(this) }
}

class MusicRepository(private val context: Context) {
    val credentials = CredentialStore(context)
    private val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val clientId = preferences.getString("clientId", null) ?: UUID.randomUUID().toString().also {
        preferences.edit().putString("clientId", it).apply()
    }
    val api = ApiClient(clientId)
    private val serverCache = ConcurrentHashMap<String, PlexServer>()
    private val endpointCache = ConcurrentHashMap<String, String>()
    private val entries = ConcurrentHashMap<String, Entry>()
    val stations: List<Entry> by lazy {
        val rows = JSONArray(context.assets.open("stations.json").bufferedReader().use { it.readText() })
        (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            Entry("radio:${row.getString("id")}", row.getString("title"), row.optString("tags"), true, row.getString("url"))
        }.also { items -> items.forEach { entries[it.id] = it } }
    }

    suspend fun browse(parent: String): List<Entry> = withContext(Dispatchers.IO) {
        when {
            parent == "root" -> listOf(Entry("radio", "Radio", "Stations"), Entry("plex", "Plex", "Your music servers"))
            parent == "radio" -> stations
            parent == "plex" -> {
                val token = credentials.get("plexToken")
                if (token.isBlank()) throw IOException("Sign in to Plex on your phone first.")
                val servers = api.servers(token)
                servers.forEach { serverCache[it.id] = it }
                servers.map { Entry("server:${it.id}", it.name, "Music libraries") }
            }
            parent.startsWith("server:") -> {
                val id = parent.removePrefix("server:")
                val payload = plexRequest(id, "/library/sections")
                val rows = payload.optJSONObject("MediaContainer")?.optJSONArray("Directory") ?: JSONArray()
                (0 until rows.length()).mapNotNull { i ->
                    val row = rows.getJSONObject(i)
                    if (row.optString("type") != "artist") null
                    else Entry("library:$id:${row.getString("key")}", row.getString("title"), "First 100 tracks (prototype)")
                }
            }
            parent.startsWith("library:") -> {
                val parts = parent.split(':', limit = 3)
                IntegrationParser.tracks(plexRequest(parts[1], "/library/sections/${parts[2]}/all?type=10&X-Plex-Container-Size=100"), parts[1])
                    .also { items -> items.forEach { entries[it.id] = it } }
            }
            else -> emptyList()
        }
    }

    suspend fun item(id: String): Entry = when {
        id == "root" -> Entry("root", "Radio Guaka")
        id == "radio" || id == "plex" -> browse("root").first { it.id == id }
        id.startsWith("server:") -> browse("plex").first { it.id == id }
        id.startsWith("library:") -> browse("server:${id.split(':', limit = 3)[1]}").first { it.id == id }
        else -> playable(id)
    }

    private suspend fun server(id: String): PlexServer {
        check(credentials.get("plexToken").isNotBlank()) { "Sign in to Plex on your phone first." }
        return serverCache[id] ?: run {
            api.servers(credentials.get("plexToken")).forEach { serverCache[it.id] = it }
            serverCache[id] ?: throw IOException("Plex server is unavailable. Refresh the server list.")
        }
    }

    private suspend fun endpoint(server: PlexServer): String = coroutineScope {
        endpointCache[server.id]?.let { return@coroutineScope it }
        // Probe concurrently so an unreachable LAN address cannot block remote playback.
        val results = Channel<String?>(Channel.UNLIMITED)
        val probes = server.connections.map { connection -> launch {
            val result = try { api.request(connection.uri + "/identity", server.token); connection.uri }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { null }
            results.send(result)
        } }
        try {
            repeat(probes.size) {
                results.receive()?.let { endpointCache[server.id] = it; return@coroutineScope it }
            }
            throw IOException("Cannot reach this Plex server. Check Plex Remote Access when away from home.")
        } finally { probes.forEach { it.cancel() }; results.close() }
    }

    private suspend fun plexRequest(id: String, path: String): JSONObject {
        val server = server(id)
        return try { api.request(endpoint(server) + path, server.token) as JSONObject }
        catch (error: IOException) {
            endpointCache.remove(id)
            api.request(endpoint(server) + path, server.token) as JSONObject
        }
    }

    suspend fun playable(id: String): Entry = withContext(Dispatchers.IO) {
        if (id.startsWith("radio:")) return@withContext stations.first { it.id == id }
        require(id.startsWith("track:")) { "Unknown media item." }
        val parts = id.split(':', limit = 3)
        val server = server(parts[1])
        val metadata = IntegrationParser.metadata(plexRequest(server.id, "/library/metadata/${parts[2]}"))
        val track = metadata.optJSONObject(0) ?: throw IOException("Track is no longer available.")
        val media = track.optJSONArray("Media")?.optJSONObject(0)
        val part = media?.optJSONArray("Part")?.optJSONObject(0)?.optString("key").orEmpty()
        if (!part.startsWith("/") || part.startsWith("//")) throw IOException("Plex did not return a playable file.")
        val base = endpoint(server)
        // Resolve only server-relative paths. Keep the token in request headers, never metadata/URLs.
        val stream = base.toHttpUrl().resolve(part) ?: throw IOException("Invalid Plex media path.")
        require(stream.host == base.toHttpUrl().host && stream.isHttps) { "Invalid Plex media host." }
        Entry(id, track.optString("title", "Track"), track.optString("grandparentTitle"), true,
            stream.toString(), server.token, plexKey = parts[2]).also { entries[id] = it }
    }

    fun savePlexToken(token: String) {
        credentials.set("plexToken", token)
        serverCache.clear(); endpointCache.clear()
        entries.keys.filter { it.startsWith("track:") }.forEach { entries.remove(it) }
    }

    fun assistantAvailable(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
    private fun requireHomeNetwork() { check(assistantAvailable()) { "Connect to your home Wi-Fi to use speakers." } }
    fun assistantUrl() = preferences.getString("assistantUrl", "").orEmpty()
    fun saveAssistant(url: String, token: String) {
        val normalized = IntegrationParser.localAssistantUrl(url)
        require(token.isNotBlank()) { "Enter a Music Assistant access token." }
        credentials.set("assistantToken", token.trim())
        preferences.edit().putString("assistantUrl", normalized).apply()
    }
    suspend fun speakers(): List<Speaker> = withContext(Dispatchers.IO) {
        requireHomeNetwork()
        IntegrationParser.speakers(api.command(assistantUrl(), credentials.get("assistantToken"), "players/all") as JSONArray)
    }
    suspend fun speakerTracks(entry: Entry): List<SpeakerTrack> = withContext(Dispatchers.IO) {
        requireHomeNetwork()
        val args = JSONObject().put("search_query", "${entry.title} ${entry.subtitle}").put("media_types", JSONArray().put("track")).put("limit", 10)
        IntegrationParser.speakerTracks(api.command(assistantUrl(), credentials.get("assistantToken"), "music/search", args) as JSONObject)
    }
    suspend fun playSpeaker(speaker: Speaker, media: String) = withContext(Dispatchers.IO) {
        requireHomeNetwork()
        api.command(assistantUrl(), credentials.get("assistantToken"), "player_queues/play_media",
            JSONObject().put("queue_id", speaker.id).put("media", JSONArray().put(media)).put("option", "replace"))
    }
}
