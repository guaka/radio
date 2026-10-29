@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package org.guaka.radio

import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class YouTubeAudioDeviceTest {
    @Test fun publicVideoResolvesAndPlaysAudioOnly() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("youtubeLive") == "true")
        val source = YouTubeAudio()
        // Blender Foundation's public Big Buck Bunny film.
        val results = source.search("Big Buck Bunny Blender", 0, 3)
        assertTrue(results.isNotEmpty())
        val entry = source.playable("youtube:aqz-KE-bpKQ")
        assertTrue(YouTubeIds.isAudioHost(entry.url))
        lateinit var player: ExoPlayer
        instrumentation.runOnMainSync {
            player = ExoPlayer.Builder(ApplicationProvider.getApplicationContext()).build()
            player.setMediaItem(entry.asMediaItem().buildUpon().setUri(entry.url).build())
            player.prepare()
            player.play()
        }
        try {
            val deadline = android.os.SystemClock.elapsedRealtime() + 40_000
            var advanced = false
            while (!advanced && android.os.SystemClock.elapsedRealtime() < deadline) {
                instrumentation.runOnMainSync {
                    player.playerError?.let { throw AssertionError("YouTube playback failed", it) }
                    advanced = player.isPlaying && player.currentPosition > 1500
                }
                Thread.sleep(100)
            }
            assertTrue("YouTube audio must advance", advanced)
            instrumentation.runOnMainSync { assertNull(player.videoFormat); assertNotNull(player.audioFormat) }
        } finally { instrumentation.runOnMainSync { player.release() } }
    }
}
