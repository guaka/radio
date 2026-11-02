@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package org.guaka.radio

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.LoadControl.Parameters
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PlaybackBufferingDeviceTest {
    private val mediaItem = MediaItem.Builder().setUri("https://example.test/audio").build()
    private val timeline = SinglePeriodTimeline(C.TIME_UNSET, true, false, false, null, mediaItem)

    @Test fun loadControlWaitsForStartupBufferBeforeStartingPlayback() {
        val loadControl = PlaybackBuffering.loadControl()
        val playerId = PlayerId("playback-buffering-startup-test")
        loadControl.onPrepared(playerId)
        try {
            assertFalse(loadControl.shouldStartPlayback(parameters(
                playerId,
                TimeUnit.MILLISECONDS.toMicros(PlaybackBuffering.BUFFER_FOR_PLAYBACK_MS - 1L),
                rebuffering = false,
            )))
            assertTrue(loadControl.shouldStartPlayback(parameters(
                playerId,
                TimeUnit.MILLISECONDS.toMicros(PlaybackBuffering.BUFFER_FOR_PLAYBACK_MS.toLong()),
                rebuffering = false,
            )))
        } finally {
            loadControl.onReleased(playerId)
        }
    }

    @Test fun loadControlWaitsForLargerBufferAfterPlaybackStall() {
        val loadControl = PlaybackBuffering.loadControl()
        val playerId = PlayerId("playback-buffering-rebuffer-test")
        loadControl.onPrepared(playerId)
        try {
            assertFalse(loadControl.shouldStartPlayback(parameters(
                playerId,
                TimeUnit.MILLISECONDS.toMicros(PlaybackBuffering.BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS - 1L),
                rebuffering = true,
            )))
            assertTrue(loadControl.shouldStartPlayback(parameters(
                playerId,
                TimeUnit.MILLISECONDS.toMicros(PlaybackBuffering.BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS.toLong()),
                rebuffering = true,
            )))
        } finally {
            loadControl.onReleased(playerId)
        }
    }

    private fun parameters(playerId: PlayerId, bufferedDurationUs: Long, rebuffering: Boolean) =
        Parameters(
            playerId,
            timeline,
            MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0)),
            0L,
            bufferedDurationUs,
            1f,
            true,
            rebuffering,
            C.TIME_UNSET,
            C.TIME_UNSET,
        )
}
