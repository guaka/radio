package org.guaka.radio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class AlbumShuffleTest {
    @Test fun scopesAreLibraryWideOrArtistSpecific() {
        assertEquals("catalog:s:1:albums", AlbumShuffle.scope("library:s:1"))
        assertEquals("catalog:s:1:albums", AlbumShuffle.scope("catalog:s:1:tracks"))
        assertEquals("artist:s:2", AlbumShuffle.scope("artist:s:2"))
        assertNull(AlbumShuffle.scope("radio"))
        assertNull(AlbumShuffle.scope("album:s:3"))
    }

    @Test fun samplingIncludesLaterPagesAndPreservesMultiDiscTrackOrder() = runBlocking {
        val visited = mutableListOf<Pair<String, Int>>()
        val firstPage = List(200) { Entry("album:s:old", "Old") }
        val tracks = List(201) { Entry("track:s:$it", "Track $it", playable = true) }
        val selection = AlbumShuffle.next("catalog:s:1:albums", "album:s:old", Random(1)) { id, page, size ->
            visited += id to page
            when (id) {
                "catalog:s:1:albums" -> if (page == 0) firstPage else listOf(Entry("album:s:new", "New"))
                else -> tracks.drop(page * size).take(size)
            }
        }
        assertEquals("album:s:new", selection.albumId)
        assertEquals(tracks.map { it.id }, selection.tracks)
        assertTrue(visited.contains("catalog:s:1:albums" to 1))
        assertTrue(visited.contains("album:s:new" to 1))
    }

    @Test fun avoidsImmediateRepeatButAllowsSingleAlbumLibrary() = runBlocking {
        for (albums in listOf(listOf("a"), listOf("a", "b"))) {
            val selection = AlbumShuffle.next("artist:s:1", "album:s:a", Random(4)) { id, _, _ ->
                if (id.startsWith("artist:")) albums.map { Entry("album:s:$it", it) }
                else listOf(Entry("track:s:1", "Track", playable = true))
            }
            assertEquals("album:s:${albums.last()}", selection.albumId)
        }
    }

    @Test fun emptyLibraryAndEmptyAlbumReportFailure() = runBlocking {
        assertTrue(runCatching { AlbumShuffle.next("artist:s:1", null) { _, _, _ -> emptyList() } }.isFailure)
        assertTrue(runCatching { AlbumShuffle.next("artist:s:1", null) { id, _, _ ->
            if (id.startsWith("artist:")) listOf(Entry("album:s:1", "Empty")) else emptyList()
        } }.isFailure)
    }

    @Test fun oversizedAlbumsAreRejectedInsteadOfTruncated() = runBlocking {
        val failure = runCatching { AlbumShuffle.next("artist:s:1", null) { id, page, size ->
            if (id.startsWith("artist:")) listOf(Entry("album:s:1", "Huge"))
            else List(501) { Entry("track:s:$it", "Track", playable = true) }.drop(page * size).take(size)
        } }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test fun cancellationPropagates() = runBlocking {
        assertTrue(runCatching { AlbumShuffle.next("artist:s:1", null) { _, _, _ ->
            throw CancellationException()
        } }.exceptionOrNull() is CancellationException)
    }

    @Test fun albumFavoritesStayBrowsableAndOutOfPlaybackQueues() = runBlocking {
        val id = "album:server:42"
        assertTrue(ListeningStore.isFavoriteMediaId(id))
        assertFalse(ListeningStore.isStableMediaId(id))
        assertFalse(ListeningStore.isFavoriteMediaId("album:server:42?token=secret"))
        assertEquals(listOf(id), ListeningStore.decodeFavoriteIds(ListeningStore.encodeFavoriteIds(listOf(id))))
        assertTrue(runCatching { ListeningStore.encodePlayback(SavedPlayback(listOf(id), 0, 0)) }.isFailure)
        val unavailable = resolveFavoriteEntries(listOf(id), false, { null }) { error("Must not resolve without credentials") }.single()
        assertEquals(id, unavailable.id)
        assertFalse(unavailable.playable)
        assertEquals("Unavailable Plex album", unavailable.title)
    }
}
