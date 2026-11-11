@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package org.guaka.radio

import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
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
        val videoId = InstrumentationRegistry.getArguments().getString("youtubeVideoId") ?: "aqz-KE-bpKQ"
        val entry = source.playable("youtube:$videoId")
        assertTrue(YouTubeIds.isAudioHost(entry.url))
        lateinit var player: ExoPlayer
        instrumentation.runOnMainSync {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val http = OkHttpDataSource.Factory(PlaybackHttpClient.forEntry(ApiClient("youtube-test").http, entry))
            val mediaSourceFactory = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http))
                .setLoadErrorHandlingPolicy(PlaybackRecovery.loadErrorPolicy(entry.id))
            player = ExoPlayer.Builder(context)
                .setMediaSourceFactory(mediaSourceFactory)
                .setLoadControl(PlaybackBuffering.loadControl())
                .build()
            player.setMediaItem(entry.asMediaItem().buildUpon().setUri(entry.url).build())
            player.prepare()
            player.play()
        }
        try {
            val deadline = android.os.SystemClock.elapsedRealtime() + 150_000
            var advanced = false
            while (!advanced && android.os.SystemClock.elapsedRealtime() < deadline) {
                instrumentation.runOnMainSync {
                    player.playerError?.let { throw AssertionError("YouTube playback failed", it) }
                    advanced = player.isPlaying && player.currentPosition > 90_000
                }
                Thread.sleep(100)
            }
            assertTrue("YouTube audio must keep playing beyond 90 seconds", advanced)
            instrumentation.runOnMainSync { assertNull(player.videoFormat); assertNotNull(player.audioFormat) }
            instrumentation.runOnMainSync { player.seekTo(120_000) }
            val seekDeadline = android.os.SystemClock.elapsedRealtime() + 40_000
            var resumed = false
            while (!resumed && android.os.SystemClock.elapsedRealtime() < seekDeadline) {
                instrumentation.runOnMainSync {
                    player.playerError?.let { throw AssertionError("YouTube seek failed", it) }
                    resumed = player.isPlaying && player.currentPosition > 125_000
                }
                Thread.sleep(100)
            }
            assertTrue("YouTube audio must resume after seeking beyond the initial buffer", resumed)
        } finally { instrumentation.runOnMainSync { player.release() } }
    }
}
