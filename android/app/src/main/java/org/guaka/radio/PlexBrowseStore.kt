package org.guaka.radio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal data class PlexBrowseLocation(val id: String, val title: String)

internal data class PlexBrowseState(
    val parent: String,
    val title: String,
    val breadcrumbs: List<PlexBrowseLocation>,
    val scrollIndex: Int,
    val scrollOffset: Int,
    val plexGeneration: Long,
) {
    companion object {
        private const val MAX_BREADCRUMBS = 24
        private const val MAX_SCROLL_INDEX = 599
        private const val MAX_SCROLL_OFFSET = 10_000
        private val KEY = Regex("[A-Za-z0-9_-]{1,128}")

        fun isPlexLocation(id: String): Boolean = id == "plex" ||
            id.startsWith("server:") || id.startsWith("library:") || id.startsWith("catalog:") ||
            id.startsWith("artist:") || id.startsWith("album:")

        private fun validLocationId(id: String): Boolean {
            if (id == "plex") return true
            val parts = id.split(':')
            return when (parts.firstOrNull()) {
                "server" -> parts.size == 2 && KEY.matches(parts[1])
                "library" -> parts.size == 3 && KEY.matches(parts[1]) && KEY.matches(parts[2])
                "catalog" -> parts.size == 4 && KEY.matches(parts[1]) && KEY.matches(parts[2]) && parts[3] in setOf("artists", "albums", "tracks")
                "artist", "album" -> parts.size == 3 && KEY.matches(parts[1]) && KEY.matches(parts[2])
                else -> false
            }
        }

        fun encode(state: PlexBrowseState): String {
            require(isPlexLocation(state.parent) && validLocationId(state.parent))
            require(state.breadcrumbs.size <= MAX_BREADCRUMBS && state.breadcrumbs.all {
                isPlexLocation(it.id) && validLocationId(it.id) && it.title.length <= 300
            })
            require(state.scrollIndex in 0..MAX_SCROLL_INDEX && state.scrollOffset in 0..MAX_SCROLL_OFFSET)
            return JSONObject()
                .put("parent", state.parent).put("title", state.title.take(300))
                .put("breadcrumbs", JSONArray().apply {
                    state.breadcrumbs.forEach { put(JSONObject().put("id", it.id).put("title", it.title.take(300))) }
                })
                .put("scrollIndex", state.scrollIndex).put("scrollOffset", state.scrollOffset)
                .put("plexGeneration", state.plexGeneration).toString()
        }

        fun decode(encoded: String?): PlexBrowseState? = runCatching {
            val json = JSONObject(encoded ?: return null)
            val parent = json.getString("parent")
            if (!isPlexLocation(parent) || !validLocationId(parent)) return null
            val raw = json.optJSONArray("breadcrumbs") ?: JSONArray()
            if (raw.length() > MAX_BREADCRUMBS) return null
            val crumbs = (0 until raw.length()).map { index ->
                val item = raw.getJSONObject(index)
                val id = item.getString("id")
                val title = item.getString("title")
                if (!isPlexLocation(id) || !validLocationId(id) || title.length > 300) return null
                PlexBrowseLocation(id, title)
            }
            val scrollIndex = json.getInt("scrollIndex")
            val scrollOffset = json.getInt("scrollOffset")
            val generation = json.getLong("plexGeneration")
            if (scrollIndex !in 0..MAX_SCROLL_INDEX || scrollOffset !in 0..MAX_SCROLL_OFFSET || generation < 0) return null
            PlexBrowseState(parent, json.optString("title", "Plex").take(300), crumbs, scrollIndex, scrollOffset, generation)
        }.getOrNull()
    }
}

internal class PlexBrowseStore(context: Context) {
    private val preferences = context.getSharedPreferences("plexBrowse", Context.MODE_PRIVATE)

    fun load(plexGeneration: Long, requireActive: Boolean = true): PlexBrowseState? {
        val state = PlexBrowseState.decode(preferences.getString(KEY_STATE, null))
        if (state != null && state.plexGeneration != plexGeneration) {
            clear()
            return null
        }
        if (requireActive && !preferences.getBoolean(KEY_ACTIVE, false)) return null
        return state
    }

    fun save(state: PlexBrowseState) {
        preferences.edit().putString(KEY_STATE, PlexBrowseState.encode(state)).putBoolean(KEY_ACTIVE, true).apply()
    }

    fun markInactive() { preferences.edit().putBoolean(KEY_ACTIVE, false).apply() }

    fun clear() { preferences.edit().remove(KEY_STATE).remove(KEY_ACTIVE).apply() }

    private companion object {
        const val KEY_STATE = "state"
        const val KEY_ACTIVE = "active"
    }
}
