package org.guaka.radio

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SavedMetadataCacheTest {
    @Test fun persistsOnlyDisplayMetadataAndServesHistoryWithoutNetworkAfterRestart() = runBlocking {
        var stored: String? = null
        fun cache() = SavedMetadataCache({ stored }, { stored = it }, { 2L })
        val initial = cache()
        val entry = Entry("youtube:aqz-KE-bpKQ", "Saved video", "Channel", true,
            url = "https://stream.example?secret=credential", token = "private-token", artwork = "https://private/art")
        initial.put(entry, 2L)
        assertFalse(stored!!.contains("https://"))
        assertFalse(stored!!.contains("private-token"))
        val reopened = cache()
        val rows = resolveFavoriteEntries(listOf(entry.id), true, reopened::get, history = true) {
            error("Cached history must not fetch metadata")
        }
        assertEquals(listOf(Entry(entry.id, entry.title, entry.subtitle, true)), rows)
    }

    @Test fun capacityIsBoundedAndAnUpdatedEntryBecomesMostRecent() {
        var stored: String? = null
        val cache = SavedMetadataCache({ stored }, { stored = it }, { 0L }, limit = 2)
        fun add(id: String, title: String = id) = cache.put(Entry("radio:$id", title, playable = true), 0L)
        add("one"); add("two"); add("one", "Updated"); add("three")
        assertNull(cache.get("radio:two"))
        assertEquals("Updated", cache.get("radio:one")?.title)
        assertNotNull(cache.get("radio:three"))
        val reopened = SavedMetadataCache({ stored }, {}, { 0L }, limit = 2)
        assertNull(reopened.get("radio:two"))
        assertNotNull(reopened.get("radio:one"))
    }

    @Test fun accountChangesRemovePlexAndRejectLateOldAccountWrites() {
        var generation = 2L
        var stored: String? = null
        val cache = SavedMetadataCache({ stored }, { stored = it }, { generation })
        cache.put(Entry("track:server:42", "Song", playable = true), 2L)
        cache.put(Entry("album:server:9", "Album"), 2L)
        cache.put(Entry("youtube:aqz-KE-bpKQ", "Video", playable = true), 2L)
        generation++
        assertNull(cache.get("track:server:42"))
        assertNull(cache.get("album:server:9"))
        assertNotNull(cache.get("youtube:aqz-KE-bpKQ"))
        cache.put(Entry("track:server:42", "Old song", playable = true), 2L)
        assertNull(cache.get("track:server:42"))
        assertFalse(stored!!.contains("Old song"))
    }

    @Test fun startupDropsPlexMetadataFromAnOlderAccountButRetainsAlbumNavigationWhenCurrent() {
        var stored: String? = null
        val initial = SavedMetadataCache({ null }, { stored = it }, { 2L })
        initial.put(Entry("album:server:9", "Album"), 2L)
        assertEquals(false, SavedMetadataCache({ stored }, {}, { 2L }).get("album:server:9")?.playable)
        assertNull(SavedMetadataCache({ stored }, {}, { 3L }).get("album:server:9"))
    }

    @Test fun corruptOrUnsupportedEntriesDoNotBecomePlayableHistory() {
        var writes = 0
        val cache = SavedMetadataCache({ "broken JSON" }, { writes++ }, { 0L })
        assertNull(cache.get("radio:one"))
        cache.put(Entry("https://example.org/audio", "URL", playable = true), 0L)
        assertEquals(0, writes)
        cache.put(Entry("radio:one", "a".repeat(2000), "b".repeat(2000), true), 0L)
        assertEquals(1000, cache.get("radio:one")!!.title.length)
        assertEquals(1000, cache.get("radio:one")!!.subtitle.length)
    }
}
