package org.guaka.radio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SpeakerPlaybackTest {
    private val a = Speaker("a", "Kitchen")
    private val b = Speaker("b", "Bedroom")
    private val c = Speaker("c", "Office")
    @Test fun synchronizedSelectionGroupsAndWaitsForAllMembers() = runBlocking {
        val groups = mutableListOf<List<Speaker>>()
        var reads = 0
        var waits = 0
        val target = synchronizedSpeakerTarget(listOf(a, b, b, c), group = { leader, members ->
            assertEquals(a, leader)
            groups += members
        }, readSpeakers = {
            reads++
            listOf(a.copy(groupMembers = if (reads == 1) setOf("b") else setOf("b", "c")))
        }, wait = { waits++ })
        assertEquals(a, target)
        assertEquals(listOf(listOf(b, c)), groups)
        assertEquals(1, waits)
    }
    @Test fun failedGroupingNeverReturnsAPlaybackTarget() = runBlocking {
        var waits = 0
        val error = runCatching {
            synchronizedSpeakerTarget(listOf(a, b), group = { _, _ -> }, readSpeakers = { listOf(a) }, wait = { waits++ })
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals(10, waits)
    }
    @Test fun singleSpeakerDoesNotChangeGroups() = runBlocking {
        val target = synchronizedSpeakerTarget(listOf(a), group = { _, _ -> fail("unexpected grouping") },
            readSpeakers = { fail("unexpected read"); emptyList() }, wait = { fail("unexpected wait") })
        assertEquals(a, target)
    }
    @Test fun deselectingSpeakerRemovesItBeforePlayback() = runBlocking {
        val removed = mutableListOf<Speaker>()
        val target = synchronizedSpeakerTarget(listOf(a), previous = listOf(a, b),
            group = { _, _ -> fail("unexpected grouping") },
            ungroup = { removed += it },
            readSpeakers = { listOf(a.copy(groupMembers = setOf("a"))) },
            wait = { fail("already detached") })
        assertEquals(a, target)
        assertEquals(listOf(b), removed)
    }
    @Test fun changingLeaderDetachesOldFollowersBeforeRegrouping() = runBlocking {
        val operations = mutableListOf<String>()
        val target = synchronizedSpeakerTarget(listOf(b, c), previous = listOf(a, b, c),
            ungroup = { operations += "ungroup:${it.joinToString { speaker -> speaker.id }}" },
            group = { leader, followers -> operations += "group:${leader.id}:${followers.single().id}" },
            readSpeakers = { listOf(a, b.copy(groupMembers = setOf("b", "c"))) },
            wait = { fail("already synchronized") })
        assertEquals(b, target)
        assertEquals(listOf("ungroup:b, c", "group:b:c"), operations)
    }
    @Test fun failedDetachDoesNotProceedToPlayback() = runBlocking {
        var grouped = false
        val error = runCatching {
            synchronizedSpeakerTarget(listOf(a), previous = listOf(a, b),
                ungroup = {}, group = { _, _ -> grouped = true },
                readSpeakers = { listOf(a.copy(groupMembers = setOf("b"))) }, wait = {})
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertFalse(grouped)
    }
    @Test fun partialFailurePreservesSuccessfulDestinationsAndTriesTheRest() = runBlocking {
        val sent = mutableListOf<String>()
        val playing = mutableListOf<List<Speaker>>()
        val result = playOnSpeakers(listOf(a, b, a, c), play = {
            sent += it.id
            if (it == b) throw IOException("offline")
        }, onStarted = { playing += it })
        assertEquals(listOf("a", "b", "c"), sent)
        assertEquals(listOf(a, c), result.started)
        assertEquals(listOf(b), result.failed)
        assertEquals(listOf(listOf(a), listOf(a, c)), playing)
    }
    @Test fun allFailuresDoNotSignalPhonePause() = runBlocking {
        var notified = false
        val result = playOnSpeakers(listOf(a, b), play = { throw IOException("offline") }, onStarted = { notified = true })
        assertTrue(result.started.isEmpty())
        assertEquals(listOf(a, b), result.failed)
        assertFalse(notified)
    }
    @Test fun cancellationStopsFurtherSpeakerCommands() = runBlocking {
        val sent = mutableListOf<Speaker>()
        var notified = false
        val error = runCatching {
            playOnSpeakers(listOf(a, b), play = { sent += it; throw CancellationException() }, onStarted = { notified = true })
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertEquals(listOf(a), sent)
        assertFalse(notified)
    }
}

class SpeakerProgressTest {
    @Test fun queueSecondsBecomeClampedTrackMillisecondsAndAdvanceOnlyWhenPlaying() {
        val parsed = SpeakerProgress.parse(org.json.JSONObject("""{"state":"playing","elapsed_time":12.5,"current_item":{"duration":180}}"""))
        assertEquals(12_500L, parsed.positionMs)
        assertEquals(180_000L, parsed.durationMs)
        assertEquals(13_500L, parsed.positionAt(parsed.sampledAtMs + 1_000))
        assertEquals(180_000L, parsed.positionAt(parsed.sampledAtMs + 999_000))
        val paused = parsed.copy(playing = false)
        assertEquals(12_500L, paused.positionAt(paused.sampledAtMs + 10_000))
    }
    @Test fun radioWithoutDurationDoesNotExposeASeekRange() {
        val radio = SpeakerProgress.parse(org.json.JSONObject("""{"state":"playing","elapsed_time":100,"current_item":{}}"""))
        assertEquals(0L, radio.durationMs)
    }
}
