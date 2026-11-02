package org.guaka.radio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class PlaybackResumptionTest {
    @Test fun restoresQueueOrderSelectedIndexAndSeekPosition() = runBlocking {
        val saved = SavedPlayback(listOf("radio:one", "track:server:42", "radio:three"), 1, 12_345L)
        val result = PlaybackResumption.resolve(saved, true, 7L, { 7L }) { id -> entry(id) }

        assertEquals(listOf("radio:one", "track:server:42", "radio:three"), result?.entries?.map { it.id })
        assertEquals(1, result?.index)
        assertEquals(12_345L, result?.positionMs)
    }

    @Test fun missingCurrentTrackFallsForwardAndResetsPosition() = runBlocking {
        val saved = SavedPlayback(listOf("radio:one", "track:server:gone", "radio:three"), 1, 12_345L)
        val result = PlaybackResumption.resolve(saved, true, 7L, { 7L }) { id ->
            if (id == "track:server:gone") throw IOException("missing")
            entry(id)
        }

        assertEquals(listOf("radio:one", "radio:three"), result?.entries?.map { it.id })
        assertEquals(1, result?.index)
        assertEquals(0L, result?.positionMs)
    }

    @Test fun unavailablePlexTracksAreSkippedWhenNoTokenIsAvailable() = runBlocking {
        val saved = SavedPlayback(listOf("track:server:42", "radio:one"), 0, 12_345L)
        val requested = mutableListOf<String>()
        val result = PlaybackResumption.resolve(saved, false, 7L, { 7L }) { id ->
            requested += id
            entry(id)
        }

        assertEquals(listOf("radio:one"), requested)
        assertEquals(listOf("radio:one"), result?.entries?.map { it.id })
        assertEquals(0, result?.index)
        assertEquals(0L, result?.positionMs)
    }

    @Test fun accountGenerationChangeDuringSuspendedResolutionDiscardsRestoredQueue() = runBlocking {
        val saved = SavedPlayback(listOf("radio:one"), 0, 0L)
        val enteredResolver = CompletableDeferred<Unit>()
        val finishResolver = CompletableDeferred<Entry>()
        var generation = 7L
        val result = async {
            PlaybackResumption.resolve(saved, true, 7L, { generation }) {
                enteredResolver.complete(Unit)
                finishResolver.await()
            }
        }

        enteredResolver.await()
        generation = 8L
        finishResolver.complete(entry("radio:one"))
        assertNull(result.await())
    }

    @Test fun cancellationPropagatesFromResolver() {
        val thrown = runCatching {
            runBlocking {
                PlaybackResumption.resolve(
                    SavedPlayback(listOf("radio:one"), 0, 0L), true, 7L, { 7L },
                ) { throw CancellationException("cancelled") }
            }
        }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
    }

    @Test fun radioSelectionAlwaysResumesLiveAtZero() = runBlocking {
        val saved = SavedPlayback(listOf("radio:one", "radio:two"), 1, 99_999L)
        val result = PlaybackResumption.resolve(saved, true, 7L, { 7L }) { entry(it) }

        assertEquals(1, result?.index)
        assertEquals(0L, result?.positionMs)
    }

    private fun entry(id: String) = Entry(id, id, playable = true, url = "https://example.test/$id")
}
