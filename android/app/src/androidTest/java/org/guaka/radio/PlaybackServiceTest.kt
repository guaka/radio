@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package org.guaka.radio

import android.content.ComponentName
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PlaybackServiceTest {
    @Test fun coldServiceExposesRadioWithoutOpeningAnActivity() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaBrowser>
        instrumentation.runOnMainSync {
            future = MediaBrowser.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
        }
        val browser = future.get(15, TimeUnit.SECONDS)
        try {
            lateinit var root: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.LibraryResult<androidx.media3.common.MediaItem>>
            instrumentation.runOnMainSync { root = browser.getLibraryRoot(null) }
            assertEquals("root", root.get(10, TimeUnit.SECONDS).value?.mediaId)
            lateinit var folder: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.LibraryResult<androidx.media3.common.MediaItem>>
            instrumentation.runOnMainSync { folder = browser.getItem("radio") }
            assertEquals(true, folder.get(10, TimeUnit.SECONDS).value?.mediaMetadata?.isBrowsable)

            lateinit var stations: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.LibraryResult<com.google.common.collect.ImmutableList<androidx.media3.common.MediaItem>>>
            instrumentation.runOnMainSync { stations = browser.getChildren("radio", 0, 10, null) }
            val items = stations.get(10, TimeUnit.SECONDS).value!!
            assertEquals(10, items.size)
            assertTrue(items.all { it.mediaMetadata.isPlayable == true })
            assertTrue(items.all { it.localConfiguration == null })
        } finally { instrumentation.runOnMainSync { browser.release() } }
    }
    // Opt in explicitly: this checks a live public stream, so it is not a hermetic CI test.
    @Test fun liveRadioSurvivesBackgroundPlayback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("liveRadio") == "true")
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.startActivity(android.content.Intent(context, MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaBrowser>
        instrumentation.runOnMainSync {
            future = MediaBrowser.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
        }
        val browser = future.get(20, TimeUnit.SECONDS)
        fun snapshot(): Pair<Boolean, Long> {
            var state = false to 0L
            instrumentation.runOnMainSync {
                assertNull("Playback error", browser.playerError)
                state = browser.isPlaying to browser.currentPosition
            }
            return state
        }
        try {
            instrumentation.runOnMainSync {
                browser.setMediaItem(androidx.media3.common.MediaItem.Builder().setMediaId("radio:7soul").build())
                browser.prepare()
                browser.play()
            }
            val deadline = android.os.SystemClock.elapsedRealtime() + 45_000
            while (!snapshot().first && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(250)
            val started = snapshot()
            assertTrue("Live station did not start", started.first)
            // Leave the activity and turn the screen off; the service must keep playing.
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            instrumentation.uiAutomation.executeShellCommand("input keyevent 223").close()
            Thread.sleep(35_000)
            val background = snapshot()
            assertTrue("Playback stopped in the background", background.first)
            assertTrue("Playback position did not advance", background.second > started.second + 25_000)
        } finally {
            instrumentation.uiAutomation.executeShellCommand("input keyevent 224").close()
            instrumentation.runOnMainSync { browser.stop(); browser.release() }
        }
    }

}
