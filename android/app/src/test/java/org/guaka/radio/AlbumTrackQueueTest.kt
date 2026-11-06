package org.guaka.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlinx.coroutines.runBlocking

class AlbumTrackQueueTest {
    private val first = Entry("track:server:11", "First", playable = true, plexParentKey = "42")
    private val selected = Entry("track:server:12", "Selected", playable = true, plexParentKey = "42")
    private val last = Entry("track:server:13", "Last", playable = true, plexParentKey = "42")

    @Test fun pagesPastFirstFiftyAndReturnsWholeAlbumWithSelectedIndex() = runBlocking {
        val all = (0 until 63).map { index ->
            Entry("track:server:${100 + index}", "Track $index", playable = true, plexParentKey = "42")
        }
        val chosen = all[53]
        val requests = mutableListOf<Int>()
        val queue = AlbumTrackQueueBuilder.resolve("album:server:42", chosen) { parent, page, size ->
            assertEquals("album:server:42", parent)
            assertEquals(50, size)
            requests += page
            all.drop(page * size).take(size)
        }

        assertEquals(all.map { it.id }, queue?.ids)
        assertEquals(53, queue?.index)
        assertEquals(listOf(0, 1), requests)
    }

    @Test fun rejectsNonAlbumAndDifferentSelectedAlbumOrServer() = runBlocking {
        assertNull(AlbumTrackQueueBuilder.resolve("radio", selected) { _, _, _ -> listOf(first, selected) })
        assertNull(AlbumTrackQueueBuilder.resolve("catalog:server:1:tracks", selected) { _, _, _ -> listOf(first, selected) })
        assertNull(AlbumTrackQueueBuilder.resolve("album:server:42", selected.copy(plexParentKey = "99")) { _, _, _ ->
            listOf(first, selected)
        })
        assertNull(AlbumTrackQueueBuilder.resolve("album:server:42", selected.copy(id = "track:other:12")) { _, _, _ ->
            listOf(first, selected)
        })
    }

    @Test fun skipsMixedSourceRowsAndStopsWhenPageMakesNoProgress() = runBlocking {
        val otherAlbum = Entry("track:server:99", "Other", playable = true, plexParentKey = "7")
        val radio = Entry("radio:station", "Radio", playable = true)
        var calls = 0
        val queue = AlbumTrackQueueBuilder.resolve("album:server:42", selected, pageSize = 4) { _, page, _ ->
            calls++
            if (page == 0) listOf(first, selected, otherAlbum, radio) else listOf(first, selected)
        }
        assertEquals(listOf(first.id, selected.id), queue?.ids)
        assertEquals(1, queue?.index)
        assertEquals(2, calls)
    }

    @Test fun rejectsSelectedTrackMissingFromAllPages() = runBlocking {
        assertNull(AlbumTrackQueueBuilder.resolve("album:server:42", selected) { _, _, _ -> listOf(first, last) })
    }
}
