package org.guaka.radio

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpeakerVolumeUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun slidersTargetTheNamedSpeakerAndDisableUnknownVolume() {
        val changes = mutableListOf<Pair<String, Int>>()
        compose.setContent {
            MaterialTheme {
                SpeakerVolumeControls(listOf(
                    SpeakerVolume(Speaker("living", "Living room"), 35),
                    SpeakerVolume(Speaker("kitchen", "Kitchen"), 60),
                    SpeakerVolume(Speaker("fixed", "Fixed output")),
                ), enabled = true) { id, level -> changes += id to level }
            }
        }
        compose.onNodeWithText("35%").assertIsDisplayed()
        compose.onNodeWithContentDescription("Volume Fixed output").assertIsNotEnabled()
        compose.onNodeWithText("Volume unavailable").assertIsDisplayed()
        compose.onNodeWithContentDescription("Volume Kitchen").performSemanticsAction(SemanticsActions.SetProgress) { it(75f) }
        compose.runOnIdle { assertEquals(listOf("kitchen" to 75), changes) }
    }

    @Test fun draggingSendsOnlyOneCommandOnRelease() {
        val changes = mutableListOf<Int>()
        compose.setContent {
            MaterialTheme {
                SpeakerVolumeControls(listOf(SpeakerVolume(Speaker("living", "Living room"), 35)), true) { _, level -> changes += level }
            }
        }
        val slider = compose.onNodeWithContentDescription("Volume Living room")
        slider.performTouchInput { down(center); moveTo(centerRight) }
        compose.runOnIdle { assertTrue(changes.isEmpty()) }
        slider.performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(1, changes.size)
            assertTrue(changes.single() > 35)
        }
    }
}
