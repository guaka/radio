package org.guaka.radio

import org.json.JSONArray
import org.json.JSONObject

/** Bounded display-only cache. Playback always resolves fresh URLs and credentials. */
internal class SavedMetadataCache(
    read: () -> String?,
    private val write: (String) -> Unit,
    private val currentGeneration: () -> Long,
    private val limit: Int = 1000,
) {
    private var generation = currentGeneration()
    private val entries = linkedMapOf<String, Entry>()

    init {
        require(limit > 0)
        runCatching {
            val json = JSONObject(read() ?: return@runCatching)
            val storedGeneration = json.optLong("generation", -1)
            val rows = json.getJSONArray("entries")
            for (i in 0 until minOf(rows.length(), limit)) {
                val row = rows.getJSONObject(i)
                val id = row.getString("id")
                if (ListeningStore.isFavoriteMediaId(id) && (!isPlex(id) || generation == storedGeneration)) {
                    entries[id] = Entry(id, row.getString("title").take(1000), row.optString("subtitle").take(1000),
                        playable = !id.startsWith("album:"))
                }
            }
            if (storedGeneration != generation) persist()
        }
    }

    @Synchronized fun get(id: String): Entry? {
        synchronizeGeneration()
        return entries[id]
    }

    @Synchronized fun put(entry: Entry, expectedGeneration: Long) {
        synchronizeGeneration()
        if (!ListeningStore.isFavoriteMediaId(entry.id) || entry.title.isBlank() ||
            (isPlex(entry.id) && expectedGeneration != generation)) return
        // Construct a new Entry rather than copying URLs, artwork, tokens or provider data.
        val clean = Entry(entry.id, entry.title.take(1000), entry.subtitle.take(1000), !entry.id.startsWith("album:"))
        if (entries[entry.id] == clean) return
        entries.remove(entry.id)
        entries[entry.id] = clean
        while (entries.size > limit) entries.remove(entries.keys.first())
        persist()
    }

    @Synchronized fun invalidatePlex() { synchronizeGeneration() }

    private fun synchronizeGeneration() {
        val updated = currentGeneration()
        if (updated == generation) return
        generation = updated
        entries.keys.removeAll { isPlex(it) }
        persist()
    }

    private fun persist() {
        val rows = JSONArray()
        entries.values.forEach { rows.put(JSONObject().put("id", it.id).put("title", it.title).put("subtitle", it.subtitle)) }
        runCatching { write(JSONObject().put("generation", generation).put("entries", rows).toString()) }
    }

    private fun isPlex(id: String) = id.startsWith("track:") || id.startsWith("album:")
}
