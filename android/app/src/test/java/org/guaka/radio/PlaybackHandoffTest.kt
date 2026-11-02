package org.guaka.radio

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class PlaybackHandoffTest {
    @Test fun activePlayerIsUntouchedUntilCandidateIsReadyThenSwappedOnce() = runBlocking {
        val handoff = PlaybackHandoff()
        val ready = CompletableDeferred<Unit>()
        var active = "old"
        var swaps = 0
        var released = 0
        val request = handoff.begin()

        val switching = async(start = CoroutineStart.UNDISPATCHED) {
            handoff.stage(request, { true }, create = { "new" }, prepare = {
                assertEquals("old", active)
                ready.await()
            }, commit = { active = it; swaps++ }, isAttached = { it == active }, discard = { released++ })
        }
        assertEquals("old", active)
        ready.complete(Unit)
        assertTrue(switching.await())
        assertEquals("new", active)
        assertEquals(1, swaps)
        assertEquals(0, released)
    }

    @Test fun failedCandidateRetainsActivePlayerAndIsReleased() = runBlocking {
        val handoff = PlaybackHandoff()
        var active = "old"
        var released = 0
        val thrown = runCatching {
            handoff.stage(handoff.begin(), { true }, create = { "bad" }, prepare = {
                throw IOException("stream failed")
            }, commit = { active = it }, isAttached = { it == active }, discard = { released++ })
        }.exceptionOrNull()

        assertTrue(thrown is IOException)
        assertEquals("old", active)
        assertEquals(1, released)
    }

    @Test fun supersededCandidateCannotCommitAndIsReleased() = runBlocking {
        val handoff = PlaybackHandoff()
        val ready = CompletableDeferred<Unit>()
        var active = "old"
        var released = 0
        val firstRequest = handoff.begin()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            handoff.stage(firstRequest, { true }, create = { "stale" }, prepare = { ready.await() },
                commit = { active = it }, isAttached = { it == active }, discard = { released++ })
        }
        val newerRequest = handoff.begin()
        ready.complete(Unit)

        assertFalse(first.await())
        assertTrue(handoff.stage(newerRequest, { true }, create = { "latest" }, prepare = {},
            commit = { active = it }, isAttached = { it == active }, discard = { released++ }))
        assertEquals("latest", active)
        assertEquals(1, released)
    }

    @Test fun pauseOrCredentialChangeInvalidatesPendingCandidate() = runBlocking {
        for (invalidate in listOf({ handoff: PlaybackHandoff -> handoff.invalidate() }, { handoff -> handoff.begin() })) {
            val handoff = PlaybackHandoff()
            val ready = CompletableDeferred<Unit>()
            var active = "old"
            var released = 0
            val request = handoff.begin()
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                handoff.stage(request, { true }, create = { "candidate" }, prepare = { ready.await() },
                    commit = { active = it }, isAttached = { it == active }, discard = { released++ })
            }
            invalidate(handoff)
            ready.complete(Unit)
            assertFalse(pending.await())
            assertEquals("old", active)
            assertEquals(1, released)
        }
    }

    @Test fun failedValidityCheckReleasesCandidateWithoutCommitting() = runBlocking {
        val handoff = PlaybackHandoff()
        var active = "old"
        var released = 0
        val committed = handoff.stage(handoff.begin(), { false }, create = { "candidate" }, prepare = {},
            commit = { active = it }, isAttached = { it == active }, discard = { released++ })
        assertFalse(committed)
        assertEquals("old", active)
        assertEquals(1, released)
    }

    @Test fun cancellationDuringPreloadReleasesCandidateWithoutCommitting() = runBlocking {
        val handoff = PlaybackHandoff()
        val entered = CompletableDeferred<Unit>()
        val neverReady = CompletableDeferred<Unit>()
        var active = "old"
        var released = 0
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            handoff.stage(handoff.begin(), { true }, create = { "candidate" }, prepare = {
                entered.complete(Unit)
                neverReady.await()
            }, commit = { active = it }, isAttached = { it == active }, discard = { released++ })
        }
        entered.await()
        pending.cancelAndJoin()
        assertEquals("old", active)
        assertEquals(1, released)
        assertTrue(runCatching { pending.await() }.exceptionOrNull() is CancellationException)
    }

    @Test fun candidateAttachedBeforeCommitThrowsIsNotReleased() = runBlocking {
        val handoff = PlaybackHandoff()
        var active = "old"
        var released = 0
        val thrown = runCatching {
            handoff.stage(handoff.begin(), { true }, create = { "candidate" }, prepare = {},
                commit = {
                    active = it
                    throw IOException("post-attachment bookkeeping failed")
                },
                isAttached = { it == active },
                discard = { released++ })
        }.exceptionOrNull()

        assertTrue(thrown is IOException)
        assertEquals("candidate", active)
        assertEquals("An attached candidate remains owned by the active player", 0, released)
    }
}
