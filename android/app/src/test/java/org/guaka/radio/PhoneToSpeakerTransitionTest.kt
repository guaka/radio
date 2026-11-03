package org.guaka.radio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class PhoneToSpeakerTransitionTest {
    private val kitchen = Speaker("kitchen", "Kitchen")

    @Test fun phoneKeepsPlayingUntilExplicitSpeakerMediaStarts() = runBlocking {
        var phonePlaying = true
        var speakerPlaying = false
        var phonePauses = 0

        val result = transitionSpeakers(
            previous = null,
            explicitMediaSelection = true,
            isCurrent = { true },
            readSourceQueue = { error("Phone playback has no Music Assistant queue") },
            synchronize = { kitchen },
            play = {
                assertEquals(kitchen, it)
                assertTrue("Phone audio should continue while the speaker starts", phonePlaying)
                speakerPlaying = true
            },
            transfer = { _, _ -> error("Phone playback cannot transfer a Music Assistant queue") },
            pausePrevious = { error("There is no previous speaker") },
        )
        if (result != null && speakerPlaying) {
            phonePlaying = false
            phonePauses++
        }

        assertEquals(SpeakerHandoffAction.PLAY, result?.action)
        assertFalse(phonePlaying)
        assertEquals(1, phonePauses)
    }

    @Test fun failedSpeakerStartLeavesPhonePlaybackRunning() = runBlocking {
        var phonePlaying = true
        var phonePauses = 0
        val failure = runCatching {
            transitionSpeakers(
                previous = null,
                explicitMediaSelection = true,
                isCurrent = { true },
                readSourceQueue = { error("Phone playback has no Music Assistant queue") },
                synchronize = { kitchen },
                play = { throw IOException("speaker could not fetch audio") },
                transfer = { _, _ -> error("Phone playback cannot transfer a Music Assistant queue") },
                pausePrevious = { error("There is no previous speaker") },
            )
        }.exceptionOrNull()
        if (failure == null) {
            phonePlaying = false
            phonePauses++
        }

        assertTrue(failure is IOException)
        assertTrue(phonePlaying)
        assertEquals(0, phonePauses)
    }

    @Test fun supersededSpeakerStartDoesNotPausePhone() = runBlocking {
        var requestCurrent = true
        var phonePlaying = true
        var phonePauses = 0
        val result = transitionSpeakers(
            previous = null,
            explicitMediaSelection = true,
            isCurrent = { requestCurrent },
            readSourceQueue = { error("Phone playback has no Music Assistant queue") },
            synchronize = { kitchen },
            play = { requestCurrent = false },
            transfer = { _, _ -> error("Phone playback cannot transfer a Music Assistant queue") },
            pausePrevious = { error("There is no previous speaker") },
        )
        if (result != null) {
            phonePlaying = false
            phonePauses++
        }

        assertEquals(null, result)
        assertTrue(phonePlaying)
        assertEquals(0, phonePauses)
    }

    @Test fun canceledSpeakerStartDoesNotPausePhone() = runBlocking {
        var phonePlaying = true
        var phonePauses = 0
        val failure = runCatching {
            transitionSpeakers(
                previous = null,
                explicitMediaSelection = true,
                isCurrent = { true },
                readSourceQueue = { error("Phone playback has no Music Assistant queue") },
                synchronize = { kitchen },
                play = { throw CancellationException("speaker request canceled") },
                transfer = { _, _ -> error("Phone playback cannot transfer a Music Assistant queue") },
                pausePrevious = { error("There is no previous speaker") },
            )
        }.exceptionOrNull()
        if (failure == null) {
            phonePlaying = false
            phonePauses++
        }

        assertTrue(failure is CancellationException)
        assertTrue(phonePlaying)
        assertEquals(0, phonePauses)
    }
}
