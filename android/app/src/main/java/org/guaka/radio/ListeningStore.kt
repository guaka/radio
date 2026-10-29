package org.guaka.radio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Persistent listening choices. This store contains media IDs and positions only, never credentials or URLs. */
class ListeningStore(context: Context) {
    private val preferences = context.getSharedPreferences("listening", Context.MODE_PRIVATE)

    @Synchronized
    fun favoriteIds(): List<String> = decodeIds(preferences.getString(KEY_FAVORITES, null))

    @Synchronized
    fun isFavorite(id: String): Boolean = isStableMediaId(id) && favoriteIds().contains(id)

    /** Adds an ID and returns true, or removes it and returns false. */
    @Synchronized
    fun toggleFavorite(id: String): Boolean {
        require(isStableMediaId(id)) { "Only stable radio and Plex track IDs can be favorited." }
        val ids = favoriteIds().toMutableList()
        val added = if (id in ids) { ids.remove(id); false } else {
            require(ids.size < MAX_FAVORITES) { "You can save up to 500 favorites. Remove one first." }
            ids.add(id); true
        }
        check(preferences.edit().putString(KEY_FAVORITES, encodeIds(ids)).commit()) { "Could not save favorites." }
        return added
    }

    fun observeChanges(onChange: () -> Unit): () -> Unit {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_FAVORITES || key == KEY_PLAYBACK) onChange()
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun plexGeneration(): Long = preferences.getLong("plexGeneration", 0L)

    fun observePlexChanges(onChange: () -> Unit): () -> Unit {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "plexGeneration") onChange()
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    @Synchronized
    fun clearPlex() {
        val remaining = favoriteIds().filterNot { it.startsWith("track:") }
        val saved = loadPlayback()
        val filtered = saved?.let { state ->
            val pairs = state.ids.mapIndexed { index, id -> id to index }
                .filterNot { it.first.startsWith("track:") }
            if (pairs.isEmpty()) null else {
                val newIndex = pairs.indexOfFirst { it.second == state.index }.takeIf { it >= 0 }
                    ?: pairs.indexOfFirst { it.second > state.index }.takeIf { it >= 0 } ?: pairs.lastIndex
                SavedPlayback(pairs.map { it.first }, newIndex, if (pairs[newIndex].second == state.index) state.positionMs else 0L)
            }
        }
        val editor = preferences.edit().putString(KEY_FAVORITES, encodeIds(remaining))
            .putLong("plexGeneration", plexGeneration() + 1L)
        if (filtered == null) editor.remove(KEY_PLAYBACK) else editor.putString(KEY_PLAYBACK, encodePlayback(filtered))
        check(editor.commit()) { "Could not clear Plex listening data." }
    }

    @Synchronized
    fun loadPlayback(): SavedPlayback? = decodePlayback(preferences.getString(KEY_PLAYBACK, null))

    @Synchronized
    fun savePlayback(state: SavedPlayback?) {
        val editor = preferences.edit()
        if (state == null) editor.remove(KEY_PLAYBACK)
        else editor.putString(KEY_PLAYBACK, encodePlayback(state))
        check(editor.commit()) { "Could not save playback state." }
    }

    companion object {
        private const val KEY_FAVORITES = "favoriteIds"
        private const val KEY_PLAYBACK = "playback"
        private const val MAX_FAVORITES = 500
        const val MAX_QUEUE = 500
        internal const val MAX_POSITION_MS = 7L * 24 * 60 * 60 * 1000
        private val RADIO_ID = Regex("radio:[a-zA-Z0-9_-]{1,80}")
        private val TRACK_ID = Regex("track:[a-zA-Z0-9_-]{1,128}:[a-zA-Z0-9_-]{1,128}")

        fun isStableMediaId(id: String): Boolean = RADIO_ID.matches(id) || TRACK_ID.matches(id)

        fun encodeIds(ids: List<String>): String = JSONArray().apply {
            ids.asSequence().filter(::isStableMediaId).distinct().take(MAX_FAVORITES).forEach { put(it) }
        }.toString()

        fun decodeIds(encoded: String?): List<String> = runCatching {
            val array = JSONArray(encoded ?: return emptyList())
            (0 until minOf(array.length(), MAX_FAVORITES)).mapNotNull { i ->
                array.optString(i).takeIf(::isStableMediaId)
            }.distinct()
        }.getOrDefault(emptyList())

        fun encodePlayback(state: SavedPlayback): String {
            require(state.ids.isNotEmpty() && state.ids.size <= MAX_QUEUE)
            require(state.ids.all(::isStableMediaId))
            require(state.index in state.ids.indices)
            require(state.positionMs in 0..MAX_POSITION_MS)
            return JSONObject().put("ids", JSONArray(state.ids)).put("index", state.index)
                .put("positionMs", state.positionMs).toString()
        }

        fun decodePlayback(encoded: String?): SavedPlayback? = runCatching {
            if (encoded.isNullOrBlank()) return null
            val json = JSONObject(encoded)
            val rawIds = json.getJSONArray("ids")
            val ids = (0 until minOf(rawIds.length(), MAX_QUEUE)).mapNotNull { i ->
                rawIds.optString(i).takeIf(::isStableMediaId)
            }
            val index = json.getInt("index")
            val position = json.getLong("positionMs")
            if (ids.isEmpty() || ids.size > MAX_QUEUE || ids.size != rawIds.length() ||
                index !in ids.indices || position !in 0..MAX_POSITION_MS) null
            else SavedPlayback(ids, index, position)
        }.getOrNull()
    }
}

data class SavedPlayback(val ids: List<String>, val index: Int, val positionMs: Long)
