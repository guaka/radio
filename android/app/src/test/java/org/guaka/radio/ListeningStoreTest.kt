package org.guaka.radio

import org.junit.Assert.*
import org.junit.Test

class ListeningStoreTest {
    @Test fun favoriteCodecKeepsOnlyStableMediaIdsAndCapsItsOutput() {
        val encoded = ListeningStore.encodeIds(listOf(
            "radio:7soul", "track:server-1:42", "https://example.org/audio", "track:server-1:42"
        ))
        assertEquals(listOf("radio:7soul", "track:server-1:42"), ListeningStore.decodeIds(encoded))
        assertTrue(ListeningStore.decodeIds("[\"radio:7soul\",\"radio:../bad\",3]").contains("radio:7soul"))
        assertFalse(ListeningStore.isStableMediaId("track:server:42?token=secret"))
        assertEquals(500, ListeningStore.decodeIds(ListeningStore.encodeIds((0..700).map { "radio:station$it" })).size)
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
}
