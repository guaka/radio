package org.guaka.radio

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in two-app test. Seed Bookstr with an EPUB before enabling it on the emulator. */
@RunWith(AndroidJUnit4::class)
class BookstrIntegrationTest {
    @Test fun opensReaderAndControlsBookstrNarrationFromRadioSession() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Set -e bookstrIntegration=true to run the two-app test.", args.getString("bookstrIntegration") == "true")
        val fixtureBookId = args.getString("bookstrFixtureBookId")
        assumeTrue("Set bookstrFixtureBookId to a seeded downloaded EPUB ID.", !fixtureBookId.isNullOrBlank())
        assumeTrue("Bookstr must be installed on the test device.", runCatching {
            ApplicationProvider.getApplicationContext<android.content.Context>()
                .packageManager.getPackageInfo(BookstrProtocol.PACKAGE_NAME, 0)
        }.isSuccess)

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val controller = BookstrController(context)
        try {
            instrumentation.runOnMainSync { controller.connect() }
            awaitState(controller, 20_000) {
                it.connected && it.books.any { book -> book.id == fixtureBookId && book.playable }
            }
            val book = controller.state.books.first { it.id == fixtureBookId }
            assertTrue("Fixture must be an EPUB with Bookstr's reader action.", book.playable && book.openReader != null)

            // Clear an earlier run's audio so that this test only accepts a fresh start.
            instrumentation.runOnMainSync {
                if (controller.state.currentBookId != null) controller.pause()
            }
            if (controller.state.currentBookId != null) {
                awaitState(controller, 5_000) { it.playbackState == android.support.v4.media.session.PlaybackStateCompat.STATE_PAUSED }
            }

            val sent = java.util.concurrent.atomic.AtomicBoolean(false)
            instrumentation.runOnMainSync { sent.set(controller.play(fixtureBookId!!)) }
            assertTrue("Bookstr reader PendingIntent must be accepted.", sent.get())
            awaitState(controller, 60_000) {
                it.currentBookId == fixtureBookId &&
                    it.playbackState == android.support.v4.media.session.PlaybackStateCompat.STATE_PLAYING
            }

            // The PendingIntent opens Bookstr in its own task; put Radio back in front before
            // exercising the cross-app MediaSession controls.
            scenario.onActivity { activity ->
                activity.startActivity(
                    Intent(activity, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                )
            }
            instrumentation.runOnMainSync { controller.pause() }
            awaitState(controller, 10_000) {
                it.currentBookId == fixtureBookId &&
                    it.playbackState == android.support.v4.media.session.PlaybackStateCompat.STATE_PAUSED
            }
            instrumentation.runOnMainSync { controller.togglePlayPause() }
            awaitState(controller, 10_000) {
                it.currentBookId == fixtureBookId &&
                    it.playbackState == android.support.v4.media.session.PlaybackStateCompat.STATE_PLAYING
            }
            assertFalse(controller.state.error?.isNotBlank() == true)
        } finally {
            instrumentation.runOnMainSync { controller.pause(); controller.close() }
            scenario.close()
        }
    }

    private fun awaitState(
        controller: BookstrController,
        timeoutMs: Long,
        predicate: (BookstrState) -> Boolean,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var last = BookstrState()
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.runOnMainSync { last = controller.state }
            if (predicate(last)) return
            Thread.sleep(50)
        }
        assertTrue("Timed out waiting for Bookstr state; last state was $last", predicate(last))
    }
}
