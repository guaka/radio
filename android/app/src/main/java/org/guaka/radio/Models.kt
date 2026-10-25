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
    val credentialGeneration: Long = 0L,
    val plexSection: String = "",
    val plexParentKey: String = "",
    val plexGrandparentKey: String = "",
)
data class PlexConnection(val uri: String, val local: Boolean, val relay: Boolean)
data class PlexServer(val id: String, val name: String, val token: String, val connections: List<PlexConnection>)
data class Pin(val id: Int, val code: String)
data class Speaker(val id: String, val name: String)
data class SpeakerTrack(val uri: String, val name: String, val artist: String)

object IntegrationParser {
    fun collectionUrl(baseUrl: String, sectionId: String, type: Int, start: Int, size: Int, title: String? = null): String {
        require(sectionId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        return baseUrl.toHttpUrl().newBuilder().addPathSegments("library/sections/$sectionId/all")
            .addQueryParameter("type", type.toString())
            .addQueryParameter("X-Plex-Container-Start", start.toString())
            .addQueryParameter("X-Plex-Container-Size", size.toString())
            .apply { if (!title.isNullOrEmpty()) addQueryParameter("title", title) }
            .build().toString()
    }

    fun childrenUrl(baseUrl: String, ratingKey: String, start: Int, size: Int): String {
        require(ratingKey.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        return baseUrl.toHttpUrl().newBuilder().addPathSegments("library/metadata/$ratingKey/children")
            .addQueryParameter("X-Plex-Container-Start", start.toString())
            .addQueryParameter("X-Plex-Container-Size", size.toString()).build().toString()
    }

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

    fun mediaEntries(payload: JSONObject, serverId: String, sectionId: String): List<Entry> =
        mediaEntries(metadata(payload), serverId, sectionId)

    fun mediaEntries(rows: JSONArray, serverId: String, sectionId: String): List<Entry> =
        (0 until rows.length()).mapNotNull { i -> mediaEntry(rows.optJSONObject(i), serverId, sectionId) }

    fun mediaEntry(row: JSONObject?, serverId: String, sectionId: String): Entry? {
        row ?: return null
        val ratingKey = row.optString("ratingKey")
        if (!ratingKey.matches(Regex("[A-Za-z0-9_-]{1,128}"))) return null
        val kind = when (row.optString("type").lowercase()) {
            "artist", "8" -> "artist"
            "album", "9" -> "album"
            "track", "10" -> "track"
            else -> return null
        }
        val title = row.optString("title").ifBlank { kind.replaceFirstChar { it.uppercase() } }
        val subtitle = when (kind) {
            "artist" -> "Artist"
            "album" -> row.optString("parentTitle", row.optString("grandparentTitle"))
            else -> listOf(row.optString("grandparentTitle"), row.optString("parentTitle"))
                .filter { it.isNotBlank() }.distinct().joinToString(" · ")
        }
        val id = "$kind:$serverId:$ratingKey"
        return Entry(id, title, subtitle, playable = kind == "track", artwork = safeArtworkPath(row.optString("thumb")), plexKey = ratingKey,
            plexSection = sectionId.ifBlank { row.optString("librarySectionID") },
            plexParentKey = row.optString("parentRatingKey"), plexGrandparentKey = row.optString("grandparentRatingKey"))
    }

    private fun safeArtworkPath(raw: String): String = raw.takeIf {
        it.startsWith('/') && !it.startsWith("//") && '?' !in it && '#' !in it &&
            !it.contains("X-Plex-Token", ignoreCase = true)
    }.orEmpty()

    fun tracks(payload: JSONObject, serverId: String): List<Entry> {
        val rows = metadata(payload)
        return (0 until rows.length()).mapNotNull { i ->
            rows.optJSONObject(i)?.put("type", "track")
            mediaEntry(rows.optJSONObject(i), serverId, "")
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
        val labels = host.split('.')
        val octets = labels.takeIf { it.size == 4 }?.mapNotNull { label ->
            label.toIntOrNull()?.takeIf { it in 0..255 }
        }.orEmpty()
        val privateV4 = labels.size == 4 && octets.size == 4 &&
            (octets[0] == 10 || (octets[0] == 192 && octets[1] == 168) ||
                (octets[0] == 172 && octets[1] in 16..31))
        val localV6 = host.contains(':') && (host.startsWith("fc") || host.startsWith("fd"))
        require(privateV4 || localV6 || host.endsWith(".local") || (!host.contains('.') && !host.contains(':'))) {
            "Use a home-network IP or hostname for Music Assistant."
        }
        return url.toString().trimEnd('/')
    }
}
