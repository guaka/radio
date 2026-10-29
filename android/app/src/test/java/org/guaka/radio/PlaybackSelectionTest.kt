package org.guaka.radio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class PlaybackSelectionTest {
    @Test fun selectionKeepsOrderIndexAndSeekPosition() = runBlocking {
        val result = PlaybackSelection.resolve(
            ids = listOf("radio:one", "track:server:42", "radio:two"), index = 1, positionMs = 43_210L,
            startingGeneration = 4L, currentGeneration = { 4L }, resolver = ::entry,
        )

        assertEquals(listOf("radio:one", "track:server:42", "radio:two"), result.entries.map { it.id })
        assertEquals(1, result.index)
        assertEquals(43_210L, result.positionMs)
    }

    @Test fun mixedHistorySelectionResolvesFreshUrlsForEachSource() = runBlocking {
        val ids = listOf("radio:one", "track:server:42", "youtube:aqz-KE-bpKQ")
        val resolved = mutableListOf<String>()
        val result = PlaybackSelection.resolve(ids, 2, 100L, 4L, { 4L }) { id ->
            synchronized(resolved) { resolved.add(id) }
            entry(id)
        }
        assertEquals(ids.toSet(), resolved.toSet())
        assertEquals(ids, result.entries.map { it.id })
        assertEquals("youtube:aqz-KE-bpKQ", result.entries[result.index].id)
        assertEquals(100L, result.positionMs)
    }

    @Test fun retryResolvesOnlySelectedSlotAndDropsUnavailableOtherQueueItems() = runBlocking {
        val requested = mutableListOf<String>()
        val existing = mapOf(0 to entry("radio:one"))
        val result = PlaybackSelection.resolve(
            ids = listOf("radio:one", "track:server:failed", "radio:three"), index = 1, positionMs = 12_000L,
            startingGeneration = 4L, currentGeneration = { 4L }, reuseEntries = existing,
            allowUnavailableUnselected = true,
        ) { id ->
            requested += id
            if (id == "track:server:failed") entry(id) else throw IOException("later entry no longer resolves")
        }

        assertEquals(listOf("track:server:failed", "radio:three"), requested)
        assertEquals(listOf("radio:one", "track:server:failed"), result.entries.map { it.id })
        assertEquals(1, result.index)
        assertEquals(12_000L, result.positionMs)
    }

    @Test fun retryFailsIfSelectedItemCannotBeResolved() {
        val thrown = runCatching {
            runBlocking {
                PlaybackSelection.resolve(
                    ids = listOf("radio:one", "track:server:failed", "radio:three"), index = 1, positionMs = 5L,
                    startingGeneration = 4L, currentGeneration = { 4L }, reuseEntries = mapOf(0 to entry("radio:one")),
                    allowUnavailableUnselected = true,
                ) { throw IOException("selected item failed") }
            }
        }.exceptionOrNull()
        assertTrue(thrown is IOException)
    }

    @Test fun radioAlwaysStartsLiveAtZeroAndGenerationChangeRejectsCandidate() = runBlocking {
        val live = PlaybackSelection.resolve(
            ids = listOf("radio:one"), index = 0, positionMs = 99_000L,
            startingGeneration = 4L, currentGeneration = { 4L }, resolver = ::entry,
        )
        assertEquals(0L, live.positionMs)

        var generation = 4L
        val thrown = runCatching {
            runBlocking {
                PlaybackSelection.resolve(
                    ids = listOf("radio:one"), index = 0, positionMs = 0L,
                    startingGeneration = 4L, currentGeneration = { generation },
                ) { generation = 5L; entry(it) }
            }
        }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
    }

    @Test fun cancellationPropagatesFromSelectedResolver() {
        val thrown = runCatching {
            runBlocking {
                PlaybackSelection.resolve(
                    ids = listOf("radio:one"), index = 0, positionMs = 0L,
                    startingGeneration = 4L, currentGeneration = { 4L },
                ) { throw CancellationException("cancelled") }
            }
        }.exceptionOrNull()
        assertTrue(thrown is CancellationException)
    }

    private fun entry(id: String) = Entry(id, id, playable = true, url = "https://example.test/$id")
}
