package org.guaka.radio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Persistent listening choices and display text, never credentials or stream URLs. */
class ListeningStore(context: Context) {
    private val preferences = context.getSharedPreferences("listening", Context.MODE_PRIVATE)

    @Synchronized
    fun favoriteIds(): List<String> = decodeFavoriteIds(preferences.getString(KEY_FAVORITES, null))

    @Synchronized
    fun isFavorite(id: String): Boolean = isFavoriteMediaId(id) && favoriteIds().contains(id)

    /** Adds an ID and returns true, or removes it and returns false. */
    @Synchronized
    fun toggleFavorite(id: String): Boolean {
        require(isFavoriteMediaId(id)) { "Only stable radio, Plex track, and Plex album IDs can be favorited." }
        val ids = favoriteIds().toMutableList()
        val added = if (id in ids) { ids.remove(id); false } else {
            require(ids.size < MAX_FAVORITES) { "You can save up to 500 favorites. Remove one first." }
            ids.add(id); true
        }
        check(preferences.edit().putString(KEY_FAVORITES, encodeFavoriteIds(ids)).commit()) { "Could not save favorites." }
        return added
    }

    @Synchronized
    internal fun replaceFavorites(ids: List<String>) {
        check(preferences.edit().putString(KEY_FAVORITES, encodeFavoriteIds(ids)).commit()) { "Could not save favorites." }
    }

    internal data class HistorySync(
        val account: String,
        val relay: String,
        val device: String,
        val local: NostrHistory.Snapshot,
        val remote: NostrHistory.Snapshot,
        val dirty: Boolean,
        val clearPending: Boolean,
        val lastPublished: Long,
    )

    @Synchronized
    internal fun historySync(): HistorySync? {
        val account = preferences.getString(KEY_HISTORY_ACCOUNT, null) ?: return null
        val relay = preferences.getString(KEY_HISTORY_RELAY, null) ?: return null
        val device = preferences.getString(KEY_HISTORY_DEVICE, null) ?: return null
        val data = readSyncState(account, relay)
        return HistorySync(account, relay, device, decodeSnapshot(data.optString("local")), decodeSnapshot(data.optString("remote")),
            data.optBoolean("dirty"), data.optBoolean("clearPending"), data.optLong("lastPublished"))
    }

    @Synchronized
    internal fun configureHistorySync(account: String, relay: String) {
        val device = preferences.getString(KEY_HISTORY_DEVICE, null) ?: UUID.randomUUID().toString()
        check(preferences.edit().putString(KEY_HISTORY_ACCOUNT, account).putString(KEY_HISTORY_RELAY, relay)
            .putString(KEY_HISTORY_DEVICE, device).commit()) { "Could not enable history sync." }
    }

    @Synchronized
    internal fun disableHistorySync() {
        check(preferences.edit().remove(KEY_HISTORY_ACCOUNT).remove(KEY_HISTORY_RELAY).commit()) { "Could not disable history sync." }
    }

    @Synchronized
    fun historyIds(): List<String> {
        val ids = localHistoryIds()
        val sync = historySync() ?: return ids
        val remoteIds = NostrHistory.merge(listOf(sync.local, sync.remote)).entries.map { it.id }
        val cutoff = maxOf(sync.local.clearBefore, sync.remote.clearBefore)
        val eligibleLocal = if (cutoff == 0L) ids else {
            val timestamps = readLocalHistoryTimes()
            ids.filter { !NostrHistory.eligible(it) || timestamps.optLong(it) > cutoff }
        }
        return (remoteIds + eligibleLocal).distinct().take(MAX_HISTORY)
    }

    @Synchronized
    fun recordPlayed(id: String) {
        if (!isStableMediaId(id)) return
        val ids = updateHistory(localHistoryIds(), id)
        val editor = preferences.edit().putString(KEY_HISTORY, encodeIds(ids))
        if (NostrHistory.eligible(id)) {
            val active = historySync()
            val at = maxOf(System.currentTimeMillis(), (active?.local?.clearBefore ?: 0) + 1,
                (active?.remote?.clearBefore ?: 0) + 1)
            val times = readLocalHistoryTimes().put(id, at)
            val bounded = JSONObject()
            ids.filter(NostrHistory::eligible).forEach { mediaId ->
                if (times.has(mediaId)) bounded.put(mediaId, times.optLong(mediaId))
            }
            editor.putString(KEY_HISTORY_TIMES, bounded.toString())
            active?.let { sync ->
                val local = NostrHistory.merge(listOf(sync.local, NostrHistory.Snapshot(entries = listOf(NostrHistory.Play(id, at)))))
                saveSync(editor, sync.copy(local = local, dirty = true))
                editor.putLong(KEY_HISTORY_WRITE, preferences.getLong(KEY_HISTORY_WRITE, 0L) + 1)
            }
        }
        check(editor.commit()) { "Could not save history." }
    }

    @Synchronized
    fun clearHistory() {
        val editor = preferences.edit().remove(KEY_HISTORY).remove(KEY_HISTORY_TIMES)
        historySync()?.let { sync ->
            val cutoff = maxOf(System.currentTimeMillis(), sync.local.clearBefore, sync.remote.clearBefore,
                sync.local.entries.maxOfOrNull { it.at } ?: 0L, sync.remote.entries.maxOfOrNull { it.at } ?: 0L)
            saveSync(editor, sync.copy(local = NostrHistory.Snapshot(cutoff), remote = NostrHistory.Snapshot(cutoff),
                dirty = true, clearPending = true))
        }
        check(editor.commit()) { "Could not clear history." }
    }

    @Synchronized
    internal fun acceptRemoteHistory(work: HistorySync, remote: List<NostrHistory.Snapshot>): HistorySync? {
        val current = currentWork(work) ?: return null
        val merged = NostrHistory.merge(listOf(NostrHistory.Snapshot(current.remote.clearBefore)) + remote)
        val editor = preferences.edit()
        val cutoff = maxOf(current.local.clearBefore, merged.clearBefore)
        val times = readLocalHistoryTimes()
        if (cutoff > 0) {
            editor.putString(KEY_HISTORY, encodeIds(localHistoryIds().filter { !NostrHistory.eligible(it) || times.optLong(it) > cutoff }))
        }
        val updated = current.copy(remote = merged)
        saveSync(editor, updated)
        check(editor.commit()) { "Could not store synced history." }
        return updated
    }

    @Synchronized
    internal fun acknowledgeHistory(work: HistorySync, publishedAt: Long) {
        val current = currentWork(work) ?: return
        val covered = current.local == work.local
        val editor = preferences.edit()
        saveSync(editor, current.copy(dirty = current.dirty && !covered,
            clearPending = current.clearPending && current.local.clearBefore > work.local.clearBefore,
            lastPublished = maxOf(current.lastPublished, publishedAt)))
        check(editor.commit()) { "Could not store the relay acknowledgement." }
    }

    private fun currentWork(work: HistorySync) = historySync()?.takeIf {
        it.account == work.account && it.relay == work.relay && it.device == work.device
    }

    private fun localHistoryIds() = decodeIds(preferences.getString(KEY_HISTORY, null))

    private fun readLocalHistoryTimes() = runCatching {
        JSONObject(preferences.getString(KEY_HISTORY_TIMES, null) ?: "{}")
    }.getOrDefault(JSONObject())

    private fun readSyncState(account: String, relay: String) = runCatching {
        JSONObject(preferences.getString("historySync:$account:$relay", null) ?: "{}")
    }.getOrDefault(JSONObject())

    private fun decodeSnapshot(value: String) = runCatching { NostrHistory.decode(value) }
        .getOrDefault(NostrHistory.Snapshot())

    private fun saveSync(editor: android.content.SharedPreferences.Editor, state: HistorySync) {
        editor.putString("historySync:${state.account}:${state.relay}", JSONObject()
            .put("local", NostrHistory.encode(state.local)).put("remote", NostrHistory.encode(state.remote))
            .put("dirty", state.dirty).put("clearPending", state.clearPending)
            .put("lastPublished", state.lastPublished).toString())
        editor.putLong(KEY_HISTORY_CHANGE, preferences.getLong(KEY_HISTORY_CHANGE, 0L) + 1)
    }

    fun observeChanges(onChange: () -> Unit): () -> Unit {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_FAVORITES || key == KEY_PLAYBACK || key == KEY_HISTORY || key == KEY_PREVIEW ||
                key == KEY_HISTORY_CHANGE || key == KEY_HISTORY_ACCOUNT) onChange()
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun plexGeneration(): Long = preferences.getLong("plexGeneration", 0L)

    internal fun observeLocalHistoryWrites(onWrite: () -> Unit): () -> Unit {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_HISTORY_WRITE) onWrite()
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun observePlexChanges(onChange: () -> Unit): () -> Unit {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "plexGeneration") onChange()
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    @Synchronized
    fun clearPlex() {
        val remaining = favoriteIds().filterNot { it.startsWith("track:") || it.startsWith("album:") }
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
        val editor = preferences.edit().putString(KEY_FAVORITES, encodeFavoriteIds(remaining))
            .putString(KEY_HISTORY, encodeIds(localHistoryIds().filterNot { it.startsWith("track:") }))
            .putLong("plexGeneration", plexGeneration() + 1L)
        if (filtered == null) editor.remove(KEY_PLAYBACK) else editor.putString(KEY_PLAYBACK, encodePlayback(filtered))
        if (playbackPreview()?.id?.startsWith("track:") == true) editor.remove(KEY_PREVIEW)
        check(editor.commit()) { "Could not clear Plex listening data." }
    }

    @Synchronized
    fun loadPlayback(): SavedPlayback? = decodePlayback(preferences.getString(KEY_PLAYBACK, null))

    @Synchronized
    fun savePlayback(state: SavedPlayback?, preview: Entry? = null) {
        val editor = preferences.edit()
        if (state == null) editor.remove(KEY_PLAYBACK)
        else editor.putString(KEY_PLAYBACK, encodePlayback(state))
        val selectedId = state?.ids?.getOrNull(state.index)
        if (preview != null && preview.id == selectedId) editor.putString(KEY_PREVIEW, encodePreview(preview))
        else if (selectedId == null || playbackPreview()?.id != selectedId) editor.remove(KEY_PREVIEW)
        check(editor.commit()) { "Could not save playback state." }
    }

    @Synchronized
    fun playbackPreview(): Entry? = decodePreview(preferences.getString(KEY_PREVIEW, null))

    companion object {
        private const val KEY_FAVORITES = "favoriteIds"
        private const val KEY_PLAYBACK = "playback"
        private const val KEY_PREVIEW = "playbackPreview"
        private const val KEY_HISTORY = "historyIds"
        private const val KEY_HISTORY_TIMES = "historyTimes"
        private const val KEY_HISTORY_ACCOUNT = "historySyncAccount"
        private const val KEY_HISTORY_RELAY = "historySyncRelay"
        private const val KEY_HISTORY_DEVICE = "historySyncDevice"
        private const val KEY_HISTORY_CHANGE = "historySyncChange"
        private const val KEY_HISTORY_WRITE = "historySyncWrite"
        private const val MAX_HISTORY = 500
        private const val MAX_FAVORITES = 500
        const val MAX_QUEUE = 500
        internal const val MAX_POSITION_MS = 7L * 24 * 60 * 60 * 1000
        internal fun encodePreview(entry: Entry): String = JSONObject()
            .put("id", entry.id).put("title", entry.title.take(1000)).put("subtitle", entry.subtitle.take(1000)).toString()

        internal fun decodePreview(encoded: String?): Entry? = runCatching {
            val json = JSONObject(encoded ?: return null)
            val id = json.getString("id")
            if (!isStableMediaId(id)) return null
            Entry(id, json.getString("title").take(1000), json.optString("subtitle").take(1000), playable = true)
        }.getOrNull()
        private val YOUTUBE_ID = Regex("youtube:[A-Za-z0-9_-]{11}")
        private val RADIO_ID = Regex("radio:[a-zA-Z0-9_-]{1,80}")
        private val TRACK_ID = Regex("track:[a-zA-Z0-9_-]{1,128}:[a-zA-Z0-9_-]{1,128}")
        private val ALBUM_ID = Regex("album:[a-zA-Z0-9_-]{1,128}:[a-zA-Z0-9_-]{1,128}")

        fun isStableMediaId(id: String): Boolean = RADIO_ID.matches(id) || TRACK_ID.matches(id) || YOUTUBE_ID.matches(id)

        /** Favorites may navigate to Plex albums; playback and history IDs stay restricted to playable media. */
        fun isFavoriteMediaId(id: String): Boolean = isStableMediaId(id) || ALBUM_ID.matches(id)

        internal fun updateHistory(ids: List<String>, id: String): List<String> =
            if (!isStableMediaId(id)) ids else (listOf(id) + ids.filterNot { it == id }).take(MAX_HISTORY)

        fun encodeIds(ids: List<String>): String = JSONArray().apply {
            ids.asSequence().filter(::isStableMediaId).distinct().take(MAX_FAVORITES).forEach { put(it) }
        }.toString()

        fun decodeIds(encoded: String?): List<String> = runCatching {
            val array = JSONArray(encoded ?: return emptyList())
            (0 until minOf(array.length(), MAX_FAVORITES)).mapNotNull { i ->
                array.optString(i).takeIf(::isStableMediaId)
            }.distinct()
        }.getOrDefault(emptyList())

        fun encodeFavoriteIds(ids: List<String>): String = JSONArray().apply {
            ids.asSequence().filter(::isFavoriteMediaId).distinct().take(MAX_FAVORITES).forEach { put(it) }
        }.toString()

        fun decodeFavoriteIds(encoded: String?): List<String> = runCatching {
            val array = JSONArray(encoded ?: return emptyList())
            (0 until minOf(array.length(), MAX_FAVORITES)).mapNotNull { i ->
                array.optString(i).takeIf(::isFavoriteMediaId)
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
