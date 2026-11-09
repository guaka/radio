package org.guaka.radio

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class TvListPolishUiTest {
    @get:Rule val compose = createAndroidComposeRule<TvActivity>()

    private fun content(
        entries: List<Entry>, favorites: Set<String> = emptySet(), onEntry: (Entry) -> Unit = {},
    ) {
        compose.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Surface(Modifier.fillMaxSize()) {
                        TvContent(
                            entries = entries, title = "Browse radio", favorites = favorites, currentTitle = "",
                            currentId = null, playing = false, ready = true, busy = false, status = "",
                            hasMore = false, canGoBack = false, listEpoch = 0, search = "", signedIn = false,
                            onSearch = {}, onSource = {}, onBack = {}, onMore = {}, onFavorite = {},
                            onSignIn = {}, onSignOut = {}, onToggle = {}, onPlay = {}, onPause = {}, onEntry = onEntry,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun entryTextIsLeftAlignedAndDpadActivatesTheRow() {
        var selected = ""
        content(listOf(Entry("station", "Station", playable = true)), onEntry = { selected = it.id })

        val row = compose.onNodeWithContentDescription("Play Station").fetchSemanticsNode().boundsInRoot
        val text = compose.onNodeWithTag("TV row text Station", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val expectedInset = with(compose.activity.resources.displayMetrics) { 12f * density }
        assertTrue("row text should start at the padded left edge", kotlin.math.abs((text.left - row.left) - expectedInset) < 3f)

        compose.onNodeWithContentDescription("Play Station").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals("station", selected) }
    }

    @Test fun settingsIsTopRightGearOnlyAndRestoresFocusAfterClosing() {
        content(emptyList())
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
        compose.onNodeWithText("Settings").assertDoesNotExist()
        val gear = compose.onNodeWithContentDescription("Settings").fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithText("Browse radio").fetchSemanticsNode().boundsInRoot
        assertTrue("settings control should be to the right of the title", gear.center.x > title.center.x)

        compose.onNodeWithContentDescription("Settings").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithContentDescription("Close settings").performClick()
        compose.onNodeWithContentDescription("Settings").assertIsFocused()
    }

    @Test fun changingFavoritesMovesRowsAheadWithoutLosingEntryOrder() {
        val favorites = mutableStateOf(emptySet<String>())
        val entries = listOf(
            Entry("station:first", "First", playable = true),
            Entry("station:second", "Second", playable = true),
        )
        compose.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Surface(Modifier.fillMaxSize()) {
                        TvContent(
                            entries = entries, title = "Radio", favorites = favorites.value, currentTitle = "",
                            currentId = null, playing = false, ready = true, busy = false, status = "",
                            hasMore = false, canGoBack = false, listEpoch = 0, search = "", signedIn = false,
                            onSearch = {}, onSource = {}, onBack = {}, onMore = {}, onFavorite = {},
                            onSignIn = {}, onSignOut = {}, onToggle = {}, onPlay = {}, onPause = {}, onEntry = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        fun top(description: String) = compose.onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot.top
        assertTrue(top("Play First") < top("Play Second"))
        compose.runOnUiThread { favorites.value = setOf("station:second") }
        compose.waitForIdle()
        assertTrue(top("Play Second") < top("Play First"))
    }
}
