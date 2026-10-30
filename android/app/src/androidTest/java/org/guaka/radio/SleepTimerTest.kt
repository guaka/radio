@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package org.guaka.radio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SleepTimerTest {
    @Test fun replacingAndCancellingTimersCannotFireAnOldAlarm() {
        val timer = SleepTimer(ApplicationProvider.getApplicationContext<Context>())
        timer.cancel()
        try {
            timer.start(15, emptySet())
            val first = requireNotNull(timer.state())
            assertNull(timer.claim(first.token, first.deadline - 1))
            timer.start(30, setOf("speaker-a"))
            val second = requireNotNull(timer.state())
            assertNull(timer.claim(first.token, Long.MAX_VALUE))
            timer.updateSpeakers(setOf("speaker-b"))
            assertEquals(setOf("speaker-b"), timer.claim(second.token, second.deadline)?.speakerIds)
            assertNull(timer.claim(second.token, Long.MAX_VALUE))
            timer.start(15, emptySet())
            val cancelled = requireNotNull(timer.state())
            timer.cancel()
            assertNull(timer.claim(cancelled.token, Long.MAX_VALUE))
        } finally {
            timer.cancel()
        }
    }

    @Test fun dueTimerPausesThePlaybackService() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val timer = (context as RadioApp).sleepTimer
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        timer.cancel()
        lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaBrowser>
        instrumentation.runOnMainSync {
            future = MediaBrowser.Builder(context, SessionToken(context, android.content.ComponentName(context, PlaybackService::class.java))).buildAsync()
        }
        val browser = future.get(15, TimeUnit.SECONDS)
        try {
            instrumentation.runOnMainSync {
                browser.clearMediaItems()
                browser.play()
            }
            val startDeadline = android.os.SystemClock.elapsedRealtime() + 5_000
            var wantsPlay = false
            while (!wantsPlay && android.os.SystemClock.elapsedRealtime() < startDeadline) {
                instrumentation.runOnMainSync { wantsPlay = browser.playWhenReady }
                if (!wantsPlay) Thread.sleep(25)
            }
            assertTrue("The player did not enter the play state", wantsPlay)
            timer.start(15, emptySet())
            context.getSharedPreferences("sleep_timer", Context.MODE_PRIVATE).edit()
                .putLong("deadline", android.os.SystemClock.elapsedRealtime() - 1).apply()
            val pauseDeadline = android.os.SystemClock.elapsedRealtime() + 10_000
            do {
                instrumentation.runOnMainSync { wantsPlay = browser.playWhenReady }
                if (!wantsPlay && timer.state() == null) break
                Thread.sleep(25)
            } while (android.os.SystemClock.elapsedRealtime() < pauseDeadline)
            assertNull("The timer did not fire", timer.state())
            assertFalse("The player was not paused", wantsPlay)
            assertNull(timer.failure())
        } finally {
            timer.cancel()
            instrumentation.runOnMainSync { browser.pause(); browser.clearMediaItems(); browser.release() }
            context.stopService(android.content.Intent(context, PlaybackService::class.java))
        }
    }
}
