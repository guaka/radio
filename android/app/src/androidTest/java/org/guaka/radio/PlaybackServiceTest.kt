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

    @Test fun invalidMediaIdsReportAnErrorWithoutReplacingTheCurrentQueue() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val error = java.util.concurrent.CompletableFuture<androidx.media3.session.SessionError>()
        lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaBrowser>
        instrumentation.runOnMainSync {
            future = MediaBrowser.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java)))
                .setListener(object : MediaBrowser.Listener {
                    override fun onError(controller: androidx.media3.session.MediaController, sessionError: androidx.media3.session.SessionError) {
                        error.complete(sessionError)
                    }
                }).buildAsync()
        }
        val browser = future.get(15, TimeUnit.SECONDS)
        try {
            instrumentation.runOnMainSync {
                browser.setMediaItem(androidx.media3.common.MediaItem.Builder().setMediaId("radio:7soul").build())
            }
            val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
            var count = 0
            while (count != 1 && android.os.SystemClock.elapsedRealtime() < deadline) {
                instrumentation.runOnMainSync { count = browser.mediaItemCount }
                Thread.sleep(25)
            }
            assertEquals(1, count)
            instrumentation.runOnMainSync {
                browser.addMediaItem(androidx.media3.common.MediaItem.Builder().setMediaId("radio:missing-station").build())
            }
            val reported = error.get(10, TimeUnit.SECONDS)
            assertEquals(androidx.media3.session.SessionError.ERROR_BAD_VALUE, reported.code)
            assertFalse(reported.message.contains("http"))
            // MediaController initially masks additions; wait for the failed-command response too.
            val rejectionDeadline = android.os.SystemClock.elapsedRealtime() + 5_000
            do {
                instrumentation.runOnMainSync { count = browser.mediaItemCount }
                if (count == 1) break
                Thread.sleep(25)
            } while (android.os.SystemClock.elapsedRealtime() < rejectionDeadline)
            instrumentation.runOnMainSync {
                assertEquals(1, browser.mediaItemCount)
                assertEquals("radio:7soul", browser.getMediaItemAt(0).mediaId)
            }
        } finally {
            instrumentation.runOnMainSync { browser.clearMediaItems(); browser.release() }
            context.stopService(android.content.Intent(context, PlaybackService::class.java))
        }
    }

    @Test fun favoritesAreBrowsableFromTheColdService() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val listening = (context.applicationContext as RadioApp).repository.listening
        val wasFavorite = listening.isFavorite("radio:7soul")
        if (!wasFavorite) listening.toggleFavorite("radio:7soul")
        lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaBrowser>
        instrumentation.runOnMainSync {
            future = MediaBrowser.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
        }
        val browser = future.get(15, TimeUnit.SECONDS)
        try {
            lateinit var folder: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.LibraryResult<androidx.media3.common.MediaItem>>
            instrumentation.runOnMainSync { folder = browser.getItem("favorites") }
            assertEquals(true, folder.get(10, TimeUnit.SECONDS).value?.mediaMetadata?.isBrowsable)
            lateinit var children: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.LibraryResult<com.google.common.collect.ImmutableList<androidx.media3.common.MediaItem>>>
            instrumentation.runOnMainSync { children = browser.getChildren("favorites", 0, 10, null) }
            val items = children.get(10, TimeUnit.SECONDS).value!!
            assertTrue(items.any { it.mediaId == "radio:7soul" && it.mediaMetadata.isPlayable == true })
        } finally {
            instrumentation.runOnMainSync { browser.release() }
            if (!wasFavorite) listening.toggleFavorite("radio:7soul")
        }
    }

    @Test fun queueStateTracksEditsAndSurvivesAnEmptyColdService() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val listening = (context.applicationContext as RadioApp).repository.listening
        listening.savePlayback(null)
        fun stopAndAwaitService() {
            context.stopService(android.content.Intent(context, PlaybackService::class.java))
            val manager = context.getSystemService(android.app.ActivityManager::class.java)
            fun running() = manager.getRunningServices(Int.MAX_VALUE).any { it.service.className == PlaybackService::class.java.name }
            val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
            while (running() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(25)
            assertFalse("Playback service did not stop", running())
        }
        stopAndAwaitService()

        fun connect(): MediaBrowser {
            lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaBrowser>
            instrumentation.runOnMainSync {
                future = MediaBrowser.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
            }
            return future.get(15, TimeUnit.SECONDS)
        }
        fun awaitSaved(expected: SavedPlayback?) {
            val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
            while (listening.loadPlayback() != expected && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(25)
            assertEquals(expected, listening.loadPlayback())
        }
        fun setRoot(browser: MediaBrowser) {
            lateinit var root: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.LibraryResult<androidx.media3.common.MediaItem>>
            instrumentation.runOnMainSync { root = browser.getLibraryRoot(null) }
            assertEquals("root", root.get(10, TimeUnit.SECONDS).value?.mediaId)
        }

        var browser: MediaBrowser? = null
        try {
            browser = connect()
            val items = listOf("radio:7soul", "radio:beatblender").map { androidx.media3.common.MediaItem.Builder().setMediaId(it).build() }
            instrumentation.runOnMainSync { browser!!.setMediaItems(items) }
            awaitSaved(SavedPlayback(listOf("radio:7soul", "radio:beatblender"), 0, 0L))

            instrumentation.runOnMainSync { browser!!.moveMediaItem(1, 0) }
            awaitSaved(SavedPlayback(listOf("radio:beatblender", "radio:7soul"), 1, 0L))

            instrumentation.runOnMainSync { browser!!.removeMediaItem(1) }
            awaitSaved(SavedPlayback(listOf("radio:beatblender"), 0, 0L))

            instrumentation.runOnMainSync { browser!!.clearMediaItems() }
            awaitSaved(null)
            instrumentation.runOnMainSync { browser!!.release() }
            browser = null
            stopAndAwaitService()

            val pending = SavedPlayback(listOf("radio:7soul", "radio:beatblender"), 1, 0L)
            listening.savePlayback(pending)
            browser = connect()
            setRoot(browser!!)
            awaitSaved(pending)
        } finally {
            browser?.let { instrumentation.runOnMainSync { it.release() } }
            stopAndAwaitService()
            listening.savePlayback(null)
        }
    }
    // Opt in explicitly: this checks a live public stream, so it is not a hermetic CI test.
    @Test fun liveRadioSurvivesBackgroundAndRespectsFocusLoss() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("liveRadio") == "true")
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val activity = instrumentation.startActivitySync(android.content.Intent(context, MainActivity::class.java)
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
                assertEquals(0, browser.mediaItemCount)
                (context.applicationContext as RadioApp).repository.listening.savePlayback(
                    SavedPlayback(listOf("radio:7soul"), 0, 0L))
                browser.play() // Exercise the service's explicit saved-queue resumption path.
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
            val audio = context.getSystemService(android.media.AudioManager::class.java)
            val interruption = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION).build())
                .setOnAudioFocusChangeListener({ }, android.os.Handler(android.os.Looper.getMainLooper())).build()
            try {
                assertEquals(android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED, audio.requestAudioFocus(interruption))
                val focusDeadline = android.os.SystemClock.elapsedRealtime() + 5_000
                while (snapshot().first && android.os.SystemClock.elapsedRealtime() < focusDeadline) Thread.sleep(50)
                assertFalse("Player ignored transient audio focus loss", snapshot().first)
                instrumentation.runOnMainSync { browser.pause() }
            } finally { audio.abandonAudioFocusRequest(interruption) }
            Thread.sleep(500)
            assertFalse("User pause must prevent resume when focus returns", snapshot().first)

        } finally {
            instrumentation.uiAutomation.executeShellCommand("input keyevent 224").close()
            instrumentation.runOnMainSync { browser.stop(); browser.clearMediaItems() }
            val store = (context.applicationContext as RadioApp).repository.listening
            val cleanupDeadline = android.os.SystemClock.elapsedRealtime() + 5_000
            while (store.loadPlayback() != null && android.os.SystemClock.elapsedRealtime() < cleanupDeadline) Thread.sleep(25)
            instrumentation.runOnMainSync { browser.release(); activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }

}
