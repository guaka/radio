package org.guaka.radio

import org.junit.Assert.*
import org.junit.Test

class TvSearchTest {
    private val station = Entry("radio:test", "Station", playable = true)
    private val track = Entry("track:test", "Track", playable = true)
    private val video = Entry("youtube:test", "Video", playable = true)

    @Test fun clearingSearchRestoresTheBrowsedCollection() {
        assertEquals(listOf(station), tvSearchEntries(" ", listOf(station), mapOf("plex" to listOf(track))))
    }

    @Test fun pendingSearchDoesNotDisplayThePreviousCollection() {
        assertTrue(tvSearchEntries("new query", listOf(station), emptyMap()).isEmpty())
    }

    @Test fun searchCombinesIndependentlyArrivingSourcesInStableOrder() {
        assertEquals(listOf(video), tvSearchEntries("query", listOf(station), mapOf("youtube" to listOf(video))))
        assertEquals(listOf(station, track, video), tvSearchEntries("query", emptyList(),
            mapOf("youtube" to listOf(video), "plex" to listOf(track), "radio" to listOf(station))))
    }
}
