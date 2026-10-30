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
