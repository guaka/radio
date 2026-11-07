package org.guaka.radio

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class TvNavigationUiTest {
    @get:Rule val compose = createAndroidComposeRule<TvActivity>()
    private var source = ""
    private var selected = ""
    private var favorite = ""
    private var toggles = 0
    private var plays = 0
    private var pauses = 0

    private fun showContent(status: String = "", trackMetadata: Boolean = false) {
        compose.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Surface {
                        TvContent(
                            entries = (0..59).map { Entry("radio:$it", "Station $it", playable = true) },
                            title = "Radio", favorites = setOf("radio:0"), currentTitle = if (trackMetadata) "Current song" else "Station 0",
                            currentArtist = if (trackMetadata) "Current artist" else null,
                            currentStation = if (trackMetadata) "Current station" else null,
                            currentId = "radio:0", playing = true, ready = true, busy = false, status = status,
                            hasMore = false, canGoBack = false, listEpoch = 0, search = "", signedIn = false,
                            onSearch = {}, onSource = { source = it }, onBack = {}, onMore = {},
                            onFavorite = { favorite = it }, onSignIn = {}, onSignOut = {},
                            onToggle = { toggles++ }, onPlay = { plays++ }, onPause = { pauses++ },
                            onEntry = { selected = it.id },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun nowPlayingShowsTrackArtistAndStation() {
        showContent(trackMetadata = true)
        compose.onNodeWithText("Current song").assertIsDisplayed()
        compose.onNodeWithText("Current artist").assertIsDisplayed()
        compose.onNodeWithText("Current station").assertIsDisplayed()
    }

    @Test fun tvLauncherResolvesWithoutATouchscreenRequirement() {
        val context = compose.activity
        compose.onNodeWithText("Radio Guaka").assertDoesNotExist()
        compose.onNodeWithContentDescription("Radio Guaka").assertIsDisplayed()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("Play 7soul").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Play 7soul").assertIsDisplayed()
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            .setPackage(context.packageName)
        assertEquals(TvActivity::class.java.name, context.packageManager.resolveActivity(launch, 0)?.activityInfo?.name)
        val info = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_CONFIGURATIONS)
        assertFalse(info.reqFeatures.orEmpty().any {
            it.name == "android.hardware.touchscreen" && it.flags and android.content.pm.FeatureInfo.FLAG_REQUIRED != 0
        })
        assertNotNull(context.packageManager.resolveActivity(launch, 0)?.activityInfo?.loadBanner(context.packageManager))
    }

    @Test fun remoteNavigatesSourcesAndActivatesFavorites() {
        showContent()
        compose.onNodeWithContentDescription("Radio").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionCenter)
        }
        compose.runOnIdle { assertEquals("favorites", source) }
        compose.onNodeWithContentDescription("Remove Station 0 from favorites")
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals("radio:0", favorite) }
    }

    @Test fun remoteScrollsBeyondVisibleRowsAndSelectsAStation() {
        showContent()
        compose.onNodeWithContentDescription("Play Station 0")
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        repeat(15) {
            compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionDown) }
            compose.waitForIdle()
        }
        compose.onNodeWithContentDescription("Play Station 15").assertIsFocused().assertIsDisplayed()
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals("radio:15", selected) }
    }

    @Test fun remoteMediaKeysAndPauseButtonControlPlayback() {
        showContent(status = "Connection interrupted. Check your network and tap Retry.")
        compose.onNodeWithText("Connection interrupted. Check your network and select the item again.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Radio").performKeyInput {
            pressKey(Key.MediaPlayPause)
            pressKey(Key.MediaPlay)
            pressKey(Key.MediaPause)
        }
        compose.runOnIdle {
            assertEquals(1, toggles)
            assertEquals(1, plays)
            assertEquals(1, pauses)
        }
        compose.onNodeWithContentDescription("Pause").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(2, toggles) }
    }
}
