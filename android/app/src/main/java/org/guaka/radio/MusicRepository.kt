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
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class RadioApp : Application() {
    override fun onCreate() { super.onCreate(); SettingsMigration.migrate(this) }
    val repository by lazy { MusicRepository(this) }
}

class MusicRepository(private val context: Context) {
    val credentials = CredentialStore(context)
    val listening = ListeningStore(context)
    private val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val clientId = preferences.getString("clientId", null) ?: UUID.randomUUID().toString().also {
        preferences.edit().putString("clientId", it).apply()
    }
    val api = ApiClient(clientId)
    private val discovery = PlexDiscoveryCache()
    private val entries = ConcurrentHashMap<String, Entry>()
    private val somaArtworkCache = SomaArtworkCache()
    private val stationArtworkCache = StationArtworkCache(
        diskDirectory = java.io.File(context.cacheDir, "station-artwork-v1")
    )
    val stations: List<Entry> by lazy {
        val rows = JSONArray(context.assets.open("stations.json").bufferedReader().use { it.readText() })
        (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            Entry("radio:${row.getString("id")}", row.getString("title").replace('_', ' '), row.optString("tags"), true,
                row.getString("url"), artwork = row.optString("artwork"))
        }.also { items -> items.forEach { entries[it.id] = it } }
    }

    suspend fun browse(parent: String, page: Int = 0, pageSize: Int = 50): List<Entry> = withContext(Dispatchers.IO) {
        val start = pageStart(page, pageSize)
        val size = safePageSize(pageSize)
        when {
            parent == "root" -> listOf(Entry("radio", "Radio", "Stations"), Entry("plex", "Plex", "Your music servers"),
                Entry("favorites", "Favorites", "Saved radio and Plex tracks")).drop(start).take(size)
            parent == "radio" -> stations.drop(start).take(size)
            parent == "favorites" -> favoriteEntries(start, size)
            parent == "plex" -> {
                val token = credentials.get("plexToken")
                if (token.isBlank()) throw ApiRequestException(401)
                val servers = discoverServers()
                servers.map { Entry("server:${it.id}", it.name, "Music libraries") }.drop(start).take(size).also(::cache)
            }
            parent.startsWith("server:") -> {
                val id = parent.removePrefix("server:")
                val payload = plexRequest(id, "/library/sections")
                artistSections(payload).map { (section, title) ->
                    Entry("library:$id:$section", title, "Artists, albums and tracks")
                }.drop(start).take(size).also(::cache)
            }
            parent.startsWith("library:") -> {
                val parts = parent.split(':', limit = 3)
                if (parts.size != 3 || !validPathKey(parts[2])) emptyList()
                else listOf("artists", "albums", "tracks").map { category ->
                    Entry("catalog:${parts[1]}:${parts[2]}:$category", category.replaceFirstChar { it.uppercase() }, when(category) {
                        "artists" -> "Browse artists"; "albums" -> "Browse albums"; else -> "Browse tracks"
                    })
                }.drop(start).take(size).also(::cache)
            }
            parent.startsWith("catalog:") -> catalogBrowse(parent, start, size)
            parent.startsWith("artist:") || parent.startsWith("album:") -> childrenBrowse(parent, start, size)
            else -> emptyList()
        }
    }

    suspend fun item(id: String): Entry = withContext(Dispatchers.IO) {
        entries[id]?.copy(url = "", token = "") ?: when {
            id == "root" -> Entry("root", "Radio Guaka")
            id in listOf("radio", "plex", "favorites") -> browse("root").first { it.id == id }
            id.startsWith("server:") -> {
                val serverId = id.removePrefix("server:")
                if (!validPathKey(serverId)) throw IOException("Unknown media item.")
                val server = server(serverId)
                Entry(id, server.name, "Music libraries")
            }
            id.startsWith("library:") -> {
                val parts = id.split(':', limit = 3)
                if (parts.size != 3 || !validPathKey(parts[1]) || !validPathKey(parts[2])) throw IOException("Unknown media item.")
                val payload = plexRequest(parts[1], "/library/sections")
                val section = artistSections(payload).firstOrNull { it.first == parts[2] }
                    ?: throw IOException("Unknown media item.")
                Entry(id, section.second, "Artists, albums and tracks")
            }
            id.startsWith("catalog:") -> {
                val parts = id.split(':', limit = 4)
                require(parts.size == 4)
                Entry(id, parts[3].replaceFirstChar { it.uppercase() }, "Browse ${parts[3]}")
            }
            id.startsWith("radio:") -> stations.first { it.id == id }
            id.startsWith("track:") || id.startsWith("artist:") || id.startsWith("album:") -> plexMetadata(id)
            else -> throw IOException("Unknown media item.")
        }.also { entries[id] = it }
    }

    suspend fun search(query: String, parent: String = "root", page: Int = 0, pageSize: Int = 50): List<Entry> = withContext(Dispatchers.IO) {
        val text = query.trim()
        if (text.isEmpty()) return@withContext emptyList()
        val start = pageStart(page, pageSize)
        val size = safePageSize(pageSize)
        val matches = mutableListOf<Entry>()
        if (parent == "root" || parent == "radio") {
            matches += stations.filter { searchable(it, text) }
        }
        if (parent == "favorites") {
            return@withContext favoriteEntries().filter { searchable(it, text) }.drop(start).take(size)
        }
        if (parent.startsWith("artist:") || parent.startsWith("album:")) {
            var offset = 0
            val results = mutableListOf<Entry>()
            while (results.size < start.toLong() + size) {
                val children = childrenBrowse(parent, offset, 200)
                results += children.filter { searchable(it, text) }
                if (children.size < 200) break
                offset += children.size
            }
            return@withContext results.drop(start).take(size)
        }
        val scopes = plexScopes(parent)
        scopes.forEach { (serverId, sectionId, requestedKind) ->
            val types = requestedKind?.let { listOf(kindType(it)) } ?: listOf(8, 9, 10)
            for (type in types) {
                val searchLimit = (start.toLong() + size).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                val url = plexCollectionUrl(serverId, sectionId, type, 0, searchLimit, text)
                val payload = plexRequest(serverId, url)
                val found = IntegrationParser.mediaEntries(payload, serverId, sectionId)
                matches += found
            }
        }
        val unique = matches.distinctBy { it.id }
        unique.drop(start).take(size).also(::cache)
    }

    private fun safePageSize(pageSize: Int) = pageSize.coerceIn(1, 200)
    private fun pageStart(page: Int, pageSize: Int): Int =
        (page.coerceAtLeast(0).toLong() * safePageSize(pageSize)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    private fun validPathKey(value: String) = value.matches(Regex("[A-Za-z0-9_-]{1,128}"))
    private fun artistSections(payload: JSONObject): List<Pair<String, String>> {
        val rows = payload.optJSONObject("MediaContainer")?.optJSONArray("Directory") ?: JSONArray()
        return (0 until rows.length()).mapNotNull { i ->
            val row = rows.optJSONObject(i) ?: return@mapNotNull null
            val section = row.optString("key")
            if (row.optString("type") != "artist" || !validPathKey(section)) null
            else section to row.optString("title", "Music")
        }
    }
    private fun cache(items: List<Entry>) { items.forEach { entries[it.id] = it } }
    private fun searchable(entry: Entry, query: String) =
        (entry.title + " " + entry.subtitle).contains(query, ignoreCase = true)

    private fun kindType(kind: String): Int = when (kind) {
        "artists" -> 8; "albums" -> 9; "tracks" -> 10
        else -> error("Unknown Plex category")
    }

    private suspend fun catalogBrowse(parent: String, start: Int, size: Int): List<Entry> {
        val parts = parent.split(':', limit = 4)
        if (parts.size != 4 || !validPathKey(parts[2])) return emptyList()
        val type = kindType(parts[3])
        val result = IntegrationParser.mediaEntries(plexRequest(parts[1], plexCollectionUrl(parts[1], parts[2], type, start, size)), parts[1], parts[2])
            .filter { it.id.startsWith(when (type) { 8 -> "artist:"; 9 -> "album:"; else -> "track:" }) }
        cache(result)
        return result
    }

    private suspend fun childrenBrowse(parent: String, start: Int, size: Int): List<Entry> {
        val parts = parent.split(':', limit = 3)
        if (parts.size != 3 || !validPathKey(parts[2])) return emptyList()
        val kind = parts[0]
        require(kind == "artist" || kind == "album")
        val server = server(parts[1])
        val url = IntegrationParser.childrenUrl(endpoint(server), parts[2], start, size).toHttpUrl()
        val section = entries[parent]?.plexSection.orEmpty()
        val result = IntegrationParser.mediaEntries(plexRequest(parts[1], url), parts[1], section)
            .filter { if (kind == "artist") it.id.startsWith("album:") else it.id.startsWith("track:") }
        cache(result)
        return result
    }

    private suspend fun plexMetadata(id: String): Entry {
        val parts = id.split(':', limit = 3)
        require(parts.size == 3 && parts[0] in listOf("track", "artist", "album") && validPathKey(parts[1]) && validPathKey(parts[2]))
        val payload = plexRequest(parts[1], "/library/metadata/${parts[2]}")
        val row = IntegrationParser.metadata(payload).optJSONObject(0)
            ?: throw ApiRequestException(404)
        if (row.optString("type").isBlank()) row.put("type", parts[0])
        return IntegrationParser.mediaEntry(row, parts[1], "")?.takeIf { it.id == id }?.also { entries[id] = it }
            ?: throw ApiRequestException(422)
    }

    private suspend fun favoriteEntries(start: Int = 0, size: Int = Int.MAX_VALUE): List<Entry> =
        resolveFavoriteEntries(
            ids = listening.favoriteIds().drop(start).take(size),
            tokenAvailable = credentials.get("plexToken").isNotBlank(),
            cachedEntry = { entries[it] },
            resolveEntry = { item(it) },
        )

    private suspend fun plexScopes(parent: String): List<Triple<String, String, String?>> {
        suspend fun sections(serverId: String): List<Triple<String, String, String?>> {
            val payload = plexRequest(serverId, "/library/sections")
            return artistSections(payload).map { (section, _) -> Triple(serverId, section, null) }
        }
        return when {
            parent == "root" || parent == "plex" -> {
                val token = credentials.get("plexToken")
                if (token.isBlank()) return emptyList()
                val servers = discoverServers()
                servers.flatMap { sections(it.id) }
            }
            parent.startsWith("server:") -> sections(parent.removePrefix("server:"))
            parent.startsWith("library:") -> parent.split(':', limit = 3).let { parts ->
                if (parts.size == 3 && validPathKey(parts[2])) listOf(Triple(parts[1], parts[2], null)) else emptyList()
            }
            parent.startsWith("catalog:") -> parent.split(':', limit = 4).let { parts ->
                if (parts.size == 4 && validPathKey(parts[2])) listOf(Triple(parts[1], parts[2], parts[3])) else emptyList()
            }
            else -> emptyList()
        }
    }

    private suspend fun plexCollectionUrl(serverId: String, sectionId: String, type: Int, start: Int, size: Int, title: String? = null): okhttp3.HttpUrl {
        require(validPathKey(sectionId))
        return IntegrationParser.collectionUrl(endpoint(server(serverId)), sectionId, type, start, size, title).toHttpUrl()
    }

    private suspend fun discoverServers(): List<PlexServer> {
        val snapshot = discovery.snapshot()
        val token = credentials.get("plexToken")
        if (token.isBlank()) throw ApiRequestException(401)
        return api.servers(token).also { discovery.storeServers(snapshot, it) }
    }

    private suspend fun server(id: String): PlexServer = discovery.server(id) ?: run {
        discoverServers()
        discovery.server(id) ?: throw IOException("Plex server is unavailable. Refresh the server list.")
    }

    private suspend fun endpoint(server: PlexServer): String = coroutineScope {
        val snapshot = discovery.snapshot()
        discovery.requireServer(snapshot, server)
        discovery.endpoint(server)?.let { return@coroutineScope it }
        // Probe concurrently so an unreachable LAN address cannot block remote playback.
        val results = Channel<Result<String>>(Channel.UNLIMITED)
        val probes = server.connections.map { connection -> launch {
            val result = try { api.request(connection.uri + "/identity", server.token); Result.success(connection.uri) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { Result.failure(e) }
            results.send(result)
        } }
        try {
            var authenticationFailure: ApiRequestException? = null
            repeat(probes.size) {
                val result = results.receive()
                result.getOrNull()?.let { discovery.storeEndpoint(snapshot, server, it); return@coroutineScope it }
                (result.exceptionOrNull() as? ApiRequestException)?.takeIf { it.statusCode in listOf(401, 403) }
                    ?.let { authenticationFailure = it }
            }
            authenticationFailure?.let { throw it }
            throw IOException("Cannot reach this Plex server. Check Plex Remote Access when away from home.")
        } finally { probes.forEach { it.cancel() }; results.close() }
    }

    private suspend fun plexRequest(id: String, path: String): JSONObject {
        val server = server(id)
        val snapshot = discovery.snapshot()
        val result = try { api.request(endpoint(server) + path, server.token) as JSONObject }
        catch (error: IOException) {
            if (!PlaybackRecovery.isRetryable(PlaybackRecovery.classify(error))) throw error
            discovery.removeEndpoint(server)
            api.request(endpoint(server) + path, server.token) as JSONObject
        }
        discovery.requireServer(snapshot, server)
        return result
    }

    private suspend fun plexRequest(id: String, url: okhttp3.HttpUrl): JSONObject {
        val pathAndQuery = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
        return plexRequest(id, pathAndQuery)
    }

    suspend fun artwork(entry: Entry): ByteArray? = withContext(Dispatchers.IO) {
        try {
            if (entry.id.startsWith("radio:")) {
                return@withContext stationArtworkCache.get(entry.id) { fetchStationArtwork(entry.id) }
            }
            if (entry.artwork.isBlank()) return@withContext null
            val parts = entry.id.split(':', limit = 3)
            if (parts.size != 3 || parts[0] !in listOf("artist", "album", "track") || !validPathKey(parts[2])) return@withContext null
            val server = server(parts[1])
            val base = endpoint(server).toHttpUrl()
            val path = entry.artwork
            if (!path.startsWith('/') || path.startsWith("//")) return@withContext null
            val image = base.resolve(path) ?: return@withContext null
            if (!image.isHttps || image.host != base.host || image.port != base.port || image.username.isNotEmpty() || image.password.isNotEmpty() || image.query != null || image.fragment != null) return@withContext null
            api.imageBytes(image.toString(), server.token, MAX_ARTWORK_BYTES)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** Gradually warms public station artwork while the owning UI remains in the foreground. */
    suspend fun preloadStationArtwork() {
        delay(ARTWORK_PRELOAD_INITIAL_DELAY_MS)
        for (station in stations) {
            currentCoroutineContext().ensureActive()
            artwork(station)
            delay(ARTWORK_PRELOAD_INTERVAL_MS)
        }
    }

    private suspend fun fetchStationArtwork(mediaId: String): ByteArray? {
        val station = stations.firstOrNull { it.id == mediaId } ?: return null
        val stationId = mediaId.removePrefix("radio:")
        val imageUrl = if (station.subtitle.split(',').any { it.trim() == "soma" }) {
            somaArtworkUrl(stationId) ?: station.artwork
        } else station.artwork
        if (imageUrl.isBlank()) return null
        val image = safeStationArtworkUrl(imageUrl) ?: return null
        return api.publicImageBytes(image.toString(), MAX_ARTWORK_BYTES)
    }

    private suspend fun somaArtworkUrl(id: String): String? {
        if (!id.matches(Regex("[A-Za-z0-9_-]{1,80}"))) return null
        return somaArtworkCache.artwork(id) {
            SomaArtwork.parse(api.publicJson(SOMA_CHANNELS_URL, SOMA_ARTWORK_HOST, MAX_SOMA_METADATA_BYTES))
        }
    }

    private fun safeStationArtworkUrl(raw: String): okhttp3.HttpUrl? {
        val parsed = runCatching { raw.toHttpUrl() }.getOrNull() ?: return null
        if (!parsed.isHttps || parsed.port != 443 || parsed.username.isNotEmpty() || parsed.password.isNotEmpty() || parsed.fragment != null) return null
        val allowedHosts = stations.mapNotNull { station ->
            station.artwork.takeIf { it.isNotBlank() }?.let { runCatching { it.toHttpUrl().host }.getOrNull() }
        }.toSet() + SOMA_ARTWORK_HOST
        return parsed.takeIf { it.host in allowedHosts }
    }


    suspend fun playable(id: String): Entry = withContext(Dispatchers.IO) {
        if (id.startsWith("radio:")) return@withContext stations.first { it.id == id }
        require(id.startsWith("track:")) { "Unknown media item." }
        val generation = listening.plexGeneration()
        val parts = id.split(':', limit = 3)
        require(parts.size == 3 && validPathKey(parts[1]) && validPathKey(parts[2])) { "Unknown media item." }
        val server = server(parts[1])
        val metadata = IntegrationParser.metadata(plexRequest(server.id, "/library/metadata/${parts[2]}"))
        val track = metadata.optJSONObject(0) ?: throw IOException("Track is no longer available.")
        if (track.optString("type").isBlank()) track.put("type", "track")
        val media = track.optJSONArray("Media")?.optJSONObject(0)
        val part = media?.optJSONArray("Part")?.optJSONObject(0)?.optString("key").orEmpty()
        if (!part.startsWith("/") || part.startsWith("//")) throw IOException("Plex did not return a playable file.")
        val base = endpoint(server)
        // Resolve only server-relative paths. Keep the token in request headers, never metadata/URLs.
        val stream = base.toHttpUrl().resolve(part) ?: throw IOException("Invalid Plex media path.")
        require(stream.host == base.toHttpUrl().host && stream.port == base.toHttpUrl().port && stream.isHttps &&
            stream.username.isEmpty() && stream.password.isEmpty() &&
            stream.queryParameterNames.none { it.equals("X-Plex-Token", ignoreCase = true) }) { "Invalid Plex media host." }
        val parsed = IntegrationParser.mediaEntry(track, server.id, "")?.takeIf { it.id == id && it.playable }
            ?: throw IOException("Plex returned unsupported track metadata.")
        check(generation == listening.plexGeneration()) { "Plex sign-in changed. Choose the track again." }
        parsed.copy(url = stream.toString(), token = server.token, credentialGeneration = generation).also { entries[id] = it }
    }

    /** Explicit user retry re-discovers endpoints after a Wi-Fi/mobile or Plex server change. */
    fun invalidatePlexConnections() { discovery.reset() }

    @Synchronized
    fun savePlexToken(token: String) {
        val previous = credentials.get("plexToken")
        discovery.reset()
        credentials.set("plexToken", token)
        discovery.reset()
        if (previous != token) listening.clearPlex()
        entries.keys.filter { it.startsWith("track:") || it.startsWith("album:") || it.startsWith("artist:") || it.startsWith("library:") || it.startsWith("catalog:") || it.startsWith("server:") }
            .forEach { entries.remove(it) }
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

    companion object {
        private const val MAX_ARTWORK_BYTES = 1024 * 1024
        private const val ARTWORK_PRELOAD_INITIAL_DELAY_MS = 1_000L
        private const val ARTWORK_PRELOAD_INTERVAL_MS = 750L
        private const val MAX_SOMA_METADATA_BYTES = 512 * 1024
        private const val SOMA_CHANNELS_URL = "https://api.somafm.com/channels.json"
        private const val SOMA_ARTWORK_HOST = "api.somafm.com"
    }
}
