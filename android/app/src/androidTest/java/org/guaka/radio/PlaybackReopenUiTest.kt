package org.guaka.radio

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackReopenUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val store get() = (context.applicationContext as RadioApp).repository.listening

    @Before fun clearSavedPlayback() {
        store.savePlayback(null)
        context.stopService(android.content.Intent(context, PlaybackService::class.java))
    }
    @After fun clearPlaybackAndStopService() {
        store.savePlayback(null)
        context.stopService(android.content.Intent(context, PlaybackService::class.java))
    }

    @Test fun recreatedActivityShowsSavedAudioWithPlayControlWithoutStartingPlayback() {
        val id = "youtube:aqz-KE-bpKQ"
        store.savePlayback(SavedPlayback(listOf(id), 0, 0L), Entry(id, "Saved video title", "Saved channel", true))
        fun launchAndAssertFreshActivity() {
            val scenario = ActivityScenario.launch(MainActivity::class.java)
            try {
                compose.waitUntil(10_000) {
                    compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Current audio: Saved video title"))
                        .fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithContentDescription("Current audio: Saved video title").assertIsDisplayed()
                compose.onNodeWithContentDescription("Play").assertIsDisplayed()
            } finally {
                scenario.close()
            }
        }

        launchAndAssertFreshActivity()
        context.stopService(android.content.Intent(context, PlaybackService::class.java))
        launchAndAssertFreshActivity()
    }
}
