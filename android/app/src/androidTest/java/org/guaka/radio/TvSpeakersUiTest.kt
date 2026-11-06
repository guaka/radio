package org.guaka.radio

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class TvSpeakersUiTest {
    @get:Rule val compose = createAndroidComposeRule<TvActivity>()
    private val speakers = listOf(Speaker("living", "Living room"), Speaker("kitchen", "Kitchen"))

    private fun dialog(
        selected: Set<String> = emptySet(), busy: Boolean = false,
        volumes: List<SpeakerVolume> = emptyList(), progress: SpeakerProgress? = null,
        onDismiss: () -> Unit = {}, onSelect: (List<Speaker>) -> Unit = {},
        onLocal: () -> Unit = {}, onVolume: (String, Int) -> Unit = { _, _ -> },
        onSeek: (Long) -> Unit = {},
    ) {
        compose.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Surface { TvSpeakerDialog(speakers, selected, volumes, busy, "", progress,
                        onDismiss, onSelect, onLocal, onVolume, onSeek) }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun speakerSelectionConfirmsOnlyChosenSpeakersAndWorksWithDpad() {
        var result: List<Speaker>? = null
        dialog(onSelect = { result = it })
        compose.onNodeWithContentDescription("Select Living room")
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithText("✓ Living room").assertExists()
        compose.onNodeWithContentDescription("Select Kitchen").performClick()
        compose.onNodeWithText("✓ Kitchen").assertExists()
        compose.onNodeWithText("Play on speakers").performClick()
        compose.runOnIdle { assertEquals(speakers, result) }
    }

    @Test fun busyDisablesSpeakerActionsAndConfirmation() {
        var selections = 0
        var local = 0
        dialog(busy = true, onSelect = { selections++ }, onLocal = { local++ })
        compose.onNodeWithContentDescription("Select Living room").assertIsNotEnabled()
        compose.onNodeWithText("This TV").assertIsNotEnabled()
        compose.onNodeWithText("Play on speakers").assertIsNotEnabled()
        compose.onNodeWithText("Updating speakers…").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, selections); assertEquals(0, local) }
    }

    @Test fun volumeButtonsClampAtBoundsAndUnavailableVolumeIsDisabled() {
        val changes = mutableListOf<Pair<String, Int>>()
        dialog(volumes = listOf(
            SpeakerVolume(speakers[0], 98), SpeakerVolume(speakers[1], 2),
            SpeakerVolume(Speaker("fixed", "Fixed output"), null),
        ), onVolume = { id, value -> changes += id to value })
        compose.onNodeWithText("Living room: 98%").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Raise volume Living room").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Lower volume Kitchen").performScrollTo().performClick()
        compose.onNodeWithText("Fixed output: Volume unavailable").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Raise volume Fixed output").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription("Lower volume Fixed output").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(listOf("living" to 100, "kitchen" to 0), changes) }
    }

    @Test fun seekButtonsClampAtTrackEdges() {
        val seeks = mutableListOf<Long>()
        dialog(progress = SpeakerProgress(positionMs = 10_000, durationMs = 20_000, playing = false), onSeek = { seeks += it })
        compose.onNodeWithText("−30 sec").performScrollTo().performClick()
        compose.onNodeWithText("+30 sec").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(0L, 20_000L), seeks) }
    }

    @Test fun seekControlsAreAbsentWithoutProgress() {
        dialog(progress = null)
        compose.onNodeWithText("Track position").assertDoesNotExist()
        compose.onNodeWithText("−30 sec").assertDoesNotExist()
        compose.onNodeWithText("+30 sec").assertDoesNotExist()
    }

    @Test fun tvContentShowsOutputNameAndOpensSpeakerPicker() {
        var opens = 0
        compose.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Surface(Modifier.fillMaxSize()) {
                        TvContent(entries = emptyList(), title = "Radio", favorites = emptySet(), currentTitle = "Live radio",
                            currentId = "radio:1", playing = true, ready = true, busy = false, status = "", hasMore = false,
                            canGoBack = false, listEpoch = 0, search = "", signedIn = false,
                            onSearch = {}, onSource = {}, onBack = {}, onMore = {}, onFavorite = {}, onSignIn = {},
                            onSignOut = {}, onToggle = {}, onPlay = {}, onPause = {}, onEntry = {},
                            outputName = "Living room", onOutputs = { opens++ })
                    }
                }
            }
        }
        compose.onNodeWithText("Playing on Living room").assertIsDisplayed()
        compose.onNodeWithText("Speakers").performClick()
        compose.runOnIdle { assertEquals(1, opens) }
    }
}
