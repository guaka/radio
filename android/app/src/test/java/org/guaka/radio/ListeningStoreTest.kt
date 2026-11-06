package org.guaka.radio

import org.junit.Assert.*
import org.junit.Test

class ListeningStoreTest {
    @Test fun favoriteCodecAcceptsAlbumsWhileHistoryAndPlaybackStayPlayableOnly() {
        val ids = listOf("radio:7soul", "track:server-1:42", "album:server-1:9", "https://example.org/audio", "album:server-1:9")
        val encoded = ListeningStore.encodeFavoriteIds(ids)
        assertEquals(listOf("radio:7soul", "track:server-1:42", "album:server-1:9"), ListeningStore.decodeFavoriteIds(encoded))
        assertTrue(ListeningStore.isFavoriteMediaId("album:server-1:9"))
        assertFalse(ListeningStore.isFavoriteMediaId("album:../bad:9"))

        val playableOnly = ListeningStore.encodeIds(ids)
        assertEquals(listOf("radio:7soul", "track:server-1:42"), ListeningStore.decodeIds(playableOnly))
        assertFalse(ListeningStore.isStableMediaId("album:server-1:9"))
        assertNull(ListeningStore.decodePlayback(
            "{\"ids\":[\"album:server-1:9\"],\"index\":0,\"positionMs\":0}"
        ))
        assertTrue(runCatching { ListeningStore.encodePlayback(SavedPlayback(listOf("album:server-1:9"), 0, 0L)) }.isFailure)
    }

    @Test fun favoriteCodecKeepsOnlyValidStableIdsAndCapsItsOutput() {
        val encoded = ListeningStore.encodeFavoriteIds(listOf(
            "radio:7soul", "track:server-1:42", "album:server-1:9", "https://example.org/audio", "track:server-1:42"
        ))
        assertEquals(listOf("radio:7soul", "track:server-1:42", "album:server-1:9"), ListeningStore.decodeFavoriteIds(encoded))
        assertTrue(ListeningStore.decodeFavoriteIds("[\"radio:7soul\",\"album:../bad:9\",3]").contains("radio:7soul"))
        assertFalse(ListeningStore.isStableMediaId("track:server:42?token=secret"))
        assertEquals(500, ListeningStore.decodeFavoriteIds(ListeningStore.encodeFavoriteIds((0..700).map { "radio:station$it" })).size)
    }

    @Test fun historyMovesPlayedItemsToFrontAndKeepsOnlyStableIds() {
        val initial = listOf("radio:7soul", "track:server:42", "youtube:aqz-KE-bpKQ")
        val updated = ListeningStore.updateHistory(initial, "track:server:42")
        assertEquals(listOf("track:server:42", "radio:7soul", "youtube:aqz-KE-bpKQ"), updated)
        assertEquals(updated, ListeningStore.decodeIds(ListeningStore.encodeIds(updated)))
        assertEquals(updated, ListeningStore.updateHistory(updated, "https://example.com/audio"))
        assertEquals(500, ListeningStore.updateHistory((0 until 500).map { "radio:station$it" }, "radio:new").size)
    }

    @Test fun playbackCodecRoundTripsQueuePositionAndRejectsInvalidData() {
        val saved = SavedPlayback(listOf("radio:7soul", "track:server-1:42", "track:server-1:42"), 1, 123_456L)
        assertEquals(saved, ListeningStore.decodePlayback(ListeningStore.encodePlayback(saved)))
        assertNull(ListeningStore.decodePlayback("{\"ids\":[\"https://example.org\"],\"index\":0,\"positionMs\":0}"))
        assertNull(ListeningStore.decodePlayback("{\"ids\":[\"radio:7soul\"],\"index\":3,\"positionMs\":0}"))
        assertNull(ListeningStore.decodePlayback("{\"ids\":[\"radio:7soul\"],\"index\":0,\"positionMs\":999999999999}"))
    }

    @Test fun previewCodecKeepsOnlyStableDisplayMetadataAndDropsStreamSecrets() {
        val entry = Entry(
            "track:server-1:42", "Saved song", "Saved artist", playable = true,
            url = "https://plex.example/audio?X-Plex-Token=secret",
        )
        val encoded = ListeningStore.encodePreview(entry)

        assertTrue(encoded.contains("Saved song"))
        assertTrue(encoded.contains("Saved artist"))
        assertFalse(encoded.contains("https://"))
        assertFalse(encoded.contains("secret"))
        assertEquals(Entry(entry.id, entry.title, entry.subtitle, playable = true), ListeningStore.decodePreview(encoded))
        assertNull(ListeningStore.decodePreview("{\"id\":\"https://example.org/audio\",\"title\":\"Secret URL\"}"))
    }
}
