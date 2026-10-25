package org.guaka.radio

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject

data class Entry(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val playable: Boolean = false,
    val url: String = "",
    val token: String = "",
    val artwork: String = "",
    val plexKey: String = "",
)
data class PlexConnection(val uri: String, val local: Boolean, val relay: Boolean)
data class PlexServer(val id: String, val name: String, val token: String, val connections: List<PlexConnection>)
data class Pin(val id: Int, val code: String)
data class Speaker(val id: String, val name: String)
data class SpeakerTrack(val uri: String, val name: String, val artist: String)

object IntegrationParser {
    fun servers(rows: JSONArray): List<PlexServer> = (0 until rows.length()).mapNotNull { i ->
        val row = rows.getJSONObject(i)
        if (!row.optString("provides").split(',').contains("server")) return@mapNotNull null
        val connections = row.optJSONArray("connections") ?: JSONArray()
        val endpoints = (0 until connections.length()).mapNotNull { j ->
            val endpoint = connections.getJSONObject(j)
            val uri = endpoint.optString("uri")
            val parsed = runCatching { uri.toHttpUrl() }.getOrNull()
            // Plex tokens travel only over HTTPS. HTTP remains available for legacy radio and local MA.
            if (parsed == null || !parsed.isHttps || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) null
            else PlexConnection(uri.trimEnd('/'), endpoint.optBoolean("local"), endpoint.optBoolean("relay"))
        }.sortedWith(compareBy<PlexConnection> { it.relay }.thenByDescending { it.local })
        val token = row.optString("accessToken")
        if (token.isBlank() || endpoints.isEmpty()) null
        else PlexServer(row.getString("clientIdentifier"), row.getString("name"), token, endpoints)
    }

    fun metadata(payload: JSONObject): JSONArray = payload.optJSONObject("MediaContainer")?.optJSONArray("Metadata") ?: JSONArray()

    fun tracks(payload: JSONObject, serverId: String): List<Entry> {
        val rows = metadata(payload)
        return (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            Entry("track:$serverId:${row.getString("ratingKey")}", row.optString("title", "Track"),
                row.optString("grandparentTitle"), playable = true, plexKey = row.getString("ratingKey"))
        }
    }

    fun speakers(rows: JSONArray): List<Speaker> = (0 until rows.length()).mapNotNull { i ->
        val row = rows.getJSONObject(i)
        if (!row.optBoolean("available", false) || row.optString("player_id").isBlank()) null
        else Speaker(row.getString("player_id"), row.optString("display_name", row.optString("name", "Speaker")))
    }

    fun speakerTracks(result: JSONObject): List<SpeakerTrack> {
        val rows = result.optJSONArray("tracks") ?: JSONArray()
        return (0 until rows.length()).mapNotNull { i ->
            val row = rows.getJSONObject(i)
            val uri = row.optString("uri")
            val artists = row.optJSONArray("artists") ?: JSONArray()
            if (uri.isBlank()) null else SpeakerTrack(uri, row.optString("name"),
                (0 until artists.length()).joinToString(", ") { artists.getJSONObject(it).optString("name") })
        }
    }

    fun localAssistantUrl(raw: String): String {
        val url = raw.trim().toHttpUrl()
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
            "Use a local Music Assistant address without credentials or query parameters."
        }
        val host = url.host.lowercase()
        val octets = host.split('.').mapNotNull { it.toIntOrNull() }
        val privateV4 = octets.size == 4 && octets.all { it in 0..255 } &&
            (octets[0] == 10 || (octets[0] == 192 && octets[1] == 168) ||
                (octets[0] == 172 && octets[1] in 16..31))
        val localV6 = host.contains(':') && (host.startsWith("fc") || host.startsWith("fd"))
        require(privateV4 || localV6 || host.endsWith(".local") || (!host.contains('.') && !host.contains(':'))) {
            "Music Assistant must use a home-network IP or hostname, such as http://192.168.1.20:8095."
        }
        return url.toString().trimEnd('/')
    }
}
