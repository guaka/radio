package org.guaka.radio

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SpeakerTransitionTest {
    private val old = Speaker("old", "Kitchen")
    private val next = Speaker("next", "Bedroom")

    @Test fun leaderChangeTransfersTheExistingQueueWithoutRestartOrExtraPause() = runBlocking {
        val calls = mutableListOf<String>()
        val result = transitionSpeakers(old, false, { true },
            readSourceQueue = { calls += "snapshot"; SpeakerQueueSnapshot("playing-queue") },
            synchronize = { calls += "group"; next },
            play = { fail("Must not restart the track") },
            transfer = { source, target ->
                assertEquals("playing-queue", source.queueId)
                assertEquals(next, target)
                calls += "transfer"
            },
            pausePrevious = { fail("Transfer owns the source queue") })
        assertEquals(listOf("snapshot", "group", "transfer"), calls)
        assertEquals(SpeakerHandoffAction.TRANSFER, result?.action)
    }

    @Test fun confirmedPlexTrackPlaysOnAnUnchangedLeader() = runBlocking {
        var played = 0
        val result = transitionSpeakers(old, true, { true },
            readSourceQueue = { fail("New media does not need the old queue"); null },
            synchronize = { old },
            play = { assertEquals(old, it); played++ },
            transfer = { _, _ -> fail("Explicit selection must play") },
            pausePrevious = { fail("Same leader must not be paused") })
        assertEquals(1, played)
        assertEquals(SpeakerHandoffAction.PLAY, result?.action)
    }

    @Test fun membershipChangeOnSameLeaderDoesNotRestartCurrentTrack() = runBlocking {
        val result = transitionSpeakers(old, false, { true },
            readSourceQueue = { SpeakerQueueSnapshot("queue") }, synchronize = { old },
            play = { fail("Must keep playing") }, transfer = { _, _ -> fail("Must keep its queue") },
            pausePrevious = { fail("Must keep playing") })
        assertEquals(SpeakerHandoffAction.KEEP, result?.action)
    }

    @Test fun supersededPlayCannotPauseTheLatestOutput() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val complete = CompletableDeferred<Unit>()
        var current = true
        val task = async(start = CoroutineStart.UNDISPATCHED) {
            transitionSpeakers(old, true, { current },
                readSourceQueue = { null }, synchronize = { next },
                play = { entered.complete(Unit); complete.await() },
                transfer = { _, _ -> fail("Not a transfer") },
                pausePrevious = { fail("Stale request must not pause current output") })
        }
        entered.await()
        current = false
        complete.complete(Unit)
        assertNull(task.await())
    }

    @Test fun failedTransferNeverFallsBackToRestartingTheTrack() = runBlocking {
        val failure = runCatching {
            transitionSpeakers(old, false, { true },
                readSourceQueue = { SpeakerQueueSnapshot("queue") }, synchronize = { next },
                play = { fail("Must not restart after transfer failure") },
                transfer = { _, _ -> throw IOException("offline") },
                pausePrevious = { fail("Source must not be paused") })
        }.exceptionOrNull()
        assertTrue(failure is IOException)
    }

    @Test fun missingSourceQueueFailsBeforeChangingGroups() = runBlocking {
        val failure = runCatching {
            transitionSpeakers(old, false, { true },
                readSourceQueue = { null }, synchronize = { fail("Must preserve the original group"); next },
                play = { fail() }, transfer = { _, _ -> fail() }, pausePrevious = { fail() })
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
    }
}
