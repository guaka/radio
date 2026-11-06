@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package org.guaka.radio

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.DefaultLoadControl
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

    @Test fun readAheadContinuesPastOldLimitAndRefillsBeforeReserveRunsLow() {
        val loadControl = PlaybackBuffering.loadControl()
        val playerId = PlayerId("playback-read-ahead-test")
        loadControl.onPrepared(playerId)
        try {
            fun loads(seconds: Long) = loadControl.shouldContinueLoading(parameters(
                playerId, TimeUnit.SECONDS.toMicros(seconds), rebuffering = false,
            ))
            assertTrue(loads(0))
            assertTrue("Keep fetching beyond the old one-minute ceiling", loads(60))
            assertTrue(loads(179))
            assertFalse("Stop at three minutes", loads(180))
            assertFalse("Keep the buffer hysteresis while above the refill threshold", loads(90))
            assertTrue("Refill while there is still a substantial reserve", loads(89))
        } finally {
            loadControl.onReleased(playerId)
        }
    }

    @Test fun largerReadAheadStillStopsAtMedia3ByteLimit() {
        val loadControl = PlaybackBuffering.loadControl()
        val playerId = PlayerId("playback-read-ahead-byte-limit-test")
        loadControl.onPrepared(playerId)
        val allocator = loadControl.getAllocator(playerId)
        val allocations = mutableListOf<androidx.media3.exoplayer.upstream.Allocation>()
        try {
            while (allocator.totalBytesAllocated < DefaultLoadControl.DEFAULT_MIN_BUFFER_SIZE) {
                allocations += allocator.allocate()
            }
            assertFalse("Longer read-ahead must not override the memory limit",
                loadControl.shouldContinueLoading(parameters(
                    playerId, TimeUnit.SECONDS.toMicros(60), rebuffering = false,
                )))
        } finally {
            allocations.forEach(allocator::release)
            loadControl.onReleased(playerId)
        }
    }

    @Test fun sixSecondsNoLongerRestartsPlaybackAfterAStall() {
        val loadControl = PlaybackBuffering.loadControl()
        val playerId = PlayerId("playback-rebuffer-reserve-test")
        loadControl.onPrepared(playerId)
        try {
            assertFalse(loadControl.shouldStartPlayback(parameters(
                playerId, TimeUnit.SECONDS.toMicros(6), rebuffering = true,
            )))
            assertTrue(loadControl.shouldStartPlayback(parameters(
                playerId, TimeUnit.SECONDS.toMicros(10), rebuffering = true,
            )))
        } finally {
            loadControl.onReleased(playerId)
        }
    }

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
