package org.guaka.radio

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class TvEnhancementsUiTest {
    @get:Rule val compose = createAndroidComposeRule<TvActivity>()

    private fun content(
        entries: List<Entry> = emptyList(),
        hasMore: Boolean = false,
        busy: Boolean = false,
        paginationFailed: Boolean = false,
        signedIn: Boolean = false,
        currentId: String? = null,
        playing: Boolean = false,
        onMore: () -> Unit = {},
        onSignIn: () -> Unit = {},
        onSignOut: () -> Unit = {},
        onToggle: () -> Unit = {},
        loadArtwork: suspend (Entry) -> ByteArray? = { null },
        loadCurrentArtwork: suspend (String) -> ByteArray? = { null },
    ) {
        compose.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Surface(Modifier.fillMaxSize()) {
                        TvContent(
                            entries = entries, title = "Radio", favorites = emptySet(), currentTitle = "",
                            currentId = currentId, playing = playing, ready = true, busy = busy, status = "",
                            hasMore = hasMore, canGoBack = false, listEpoch = 0, search = "", signedIn = signedIn,
                            onSearch = {}, onSource = {}, onBack = {}, onMore = onMore,
                            onFavorite = {}, onSignIn = onSignIn, onSignOut = onSignOut,
                            onToggle = onToggle, onPlay = {}, onPause = {}, onEntry = {},
                            paginationFailed = paginationFailed, accountName = "Test account",
                            loadArtwork = loadArtwork, loadCurrentArtwork = loadCurrentArtwork,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun entries(count: Int, start: Int = 0) =
        (start until start + count).map { Entry("radio:$it", "Station $it", playable = true) }

    @Test fun settingsCallsSignInAndSignOutAndRestoresDpadFocus() {
        var signIns = 0
        content(onSignIn = { signIns++ })
        compose.onNodeWithContentDescription("Sign in to Plex").assertDoesNotExist()
        compose.onNodeWithContentDescription("Settings")
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Sign in to Plex").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1, signIns) }
        compose.onNodeWithContentDescription("Close settings").performClick()
        compose.onNodeWithContentDescription("Settings").assertIsFocused()
        compose.onNodeWithContentDescription("Settings").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Settings").assertIsFocused()

        var signOuts = 0
        content(signedIn = true, onSignOut = { signOuts++ })
        compose.onNodeWithContentDescription("Settings")
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Sign out of Plex").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1, signOuts) }
    }

    @Test fun paginationRequestsNearEndAndRefillsAcrossShortPages() {
        val entriesState = mutableStateOf(entries(2))
        val hasMoreState = mutableStateOf(true)
        var requests = 0
        compose.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Surface {
                        TvContent(
                            entries = entriesState.value, title = "Radio", favorites = emptySet(), currentTitle = "",
                            currentId = null, playing = false, ready = true, busy = false, status = "",
                            hasMore = hasMoreState.value, canGoBack = false, listEpoch = 0, search = "", signedIn = false,
                            onSearch = {}, onSource = {}, onBack = {}, onMore = {
                                requests++
                                if (requests < 3) entriesState.value = entriesState.value + entries(2, entriesState.value.size)
                                else hasMoreState.value = false
                            }, onFavorite = {}, onSignIn = {}, onSignOut = {}, onToggle = {}, onPlay = {}, onPause = {}, onEntry = {},
                        )
                    }
                }
            }
        }
        compose.waitUntil(10_000) { compose.runOnIdle { requests >= 3 } }
        compose.runOnIdle {
            assertEquals(3, requests)
            assertEquals(6, entriesState.value.size)
            assertFalse(hasMoreState.value)
        }
    }

    @Test fun dpadScrollingNearEndAppendsAndKeepsFocusedEntry() {
        val entriesState = mutableStateOf(entries(30))
        val hasMoreState = mutableStateOf(true)
        val busyState = mutableStateOf(false)
        var requests = 0
        compose.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Surface {
                        TvContent(
                            entries = entriesState.value, title = "Radio", favorites = emptySet(), currentTitle = "",
                            currentId = null, playing = false, ready = true, busy = busyState.value, status = "",
                            hasMore = hasMoreState.value, canGoBack = false, listEpoch = 0, search = "", signedIn = false,
                            onSearch = {}, onSource = {}, onBack = {}, onMore = {
                                requests++
                                busyState.value = true
                            }, onFavorite = {}, onSignIn = {}, onSignOut = {}, onToggle = {}, onPlay = {}, onPause = {}, onEntry = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Play Station 0")
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            .assertIsFocused()
        var focusBeforeAppend: List<String>? = null
        repeat(28) {
            if (!compose.runOnIdle { requests > 0 }) {
                compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionDown) }
                compose.waitForIdle()
                if (compose.runOnIdle { requests > 0 }) {
                    focusBeforeAppend = compose.onNode(isFocused()).fetchSemanticsNode().config[SemanticsProperties.ContentDescription]
                }
            }
        }
        compose.waitUntil(10_000) { compose.runOnIdle { requests == 1 } }
        compose.runOnUiThread {
            entriesState.value = entriesState.value + entries(5, entriesState.value.size)
            hasMoreState.value = false
            busyState.value = false
        }
        compose.waitForIdle()
        val focusedAfter = compose.onNode(isFocused()).fetchSemanticsNode().config[SemanticsProperties.ContentDescription]
        compose.runOnIdle {
            assertEquals(1, requests)
            assertEquals(35, entriesState.value.size)
        }
        assertTrue(focusBeforeAppend.orEmpty().any { it.startsWith("Play Station ") })
        assertEquals(focusBeforeAppend, focusedAfter)
        compose.onNodeWithText("Refresh").assertDoesNotExist()
        compose.onNodeWithText("Load more").assertDoesNotExist()
    }

    @Test fun paginationHonorsBusyHasMoreAndFailureGatesAndRetry() {
        var requests = 0
        content(entries = entries(2), hasMore = true, busy = true, onMore = { requests++ })
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, requests) }

        content(entries = entries(2), hasMore = false, onMore = { requests++ })
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, requests) }

        content(entries = entries(2), hasMore = true, paginationFailed = true, onMore = { requests++ })
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, requests) }
        compose.onNodeWithContentDescription("Retry loading").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, requests) }

        content(entries = entries(2), hasMore = true, paginationFailed = true, busy = true, onMore = { requests++ })
        compose.onNodeWithContentDescription("Retry loading").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, requests) }
    }

    @Test fun artworkShowsDecodedImageAndFallbackForMissingOrCorruptBytes() {
        val valid = bitmapBytes()
        compose.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Column {
                        TvArtwork("valid", "", 48, "Valid artwork") { valid }
                        TvArtwork("missing", "", 48, "Missing artwork") { null }
                        TvArtwork("corrupt", "", 48, "Corrupt artwork") { byteArrayOf(1, 2, 3) }
                    }
                }
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("Valid artwork image").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag("Missing artwork fallback").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag("Corrupt artwork fallback").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("Valid artwork image").assertExists()
        compose.onNodeWithTag("Missing artwork fallback").assertExists()
        compose.onNodeWithTag("Corrupt artwork fallback").assertExists()
    }

    @Test fun playbackControlsKeepAccessiblePlayPauseDescriptionsWithoutTextLabels() {
        var toggles = 0
        content(currentId = "radio:0", playing = false, onToggle = { toggles++ })
        compose.onNodeWithContentDescription("Play").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, toggles) }
        compose.onNodeWithText("Play").assertDoesNotExist()

        content(currentId = "radio:0", playing = true, onToggle = { toggles++ })
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(2, toggles) }
        compose.onNodeWithText("Pause").assertDoesNotExist()
    }

    @Test fun artworkFromObsoleteLoadCannotReplaceCurrentArtwork() {
        val oldStarted = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        val currentId = mutableStateOf("old")
        val valid = bitmapBytes()
        compose.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    TvArtwork(currentId.value, "", 48, "Current artwork") {
                        if (currentId.value == "old") {
                            oldStarted.countDown()
                            releaseOld.await(5, TimeUnit.SECONDS)
                            byteArrayOf(1, 2, 3)
                        } else valid
                    }
                }
            }
        }
        compose.waitUntil(10_000) { oldStarted.count == 0L }
        compose.runOnUiThread { currentId.value = "current" }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("Current artwork image").fetchSemanticsNodes().isNotEmpty() }
        releaseOld.countDown()
        compose.waitForIdle()
        compose.onNodeWithTag("Current artwork image").assertExists()
        compose.onNodeWithTag("Current artwork fallback").assertDoesNotExist()
    }

    @Test fun nowPlayingArtworkLoadsAndReloadsIndependentlyOfVisibleEntries() {
        val currentId = mutableStateOf("episode:one")
        val requestedIds = CopyOnWriteArrayList<String>()
        val valid = bitmapBytes()
        compose.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Surface {
                        TvContent(
                            entries = emptyList(), title = "Radio", favorites = emptySet(), currentTitle = "Current",
                            currentId = currentId.value, playing = true, ready = true, busy = false, status = "",
                            hasMore = false, canGoBack = false, listEpoch = 0, search = "", signedIn = false,
                            onSearch = {}, onSource = {}, onBack = {}, onMore = {}, onFavorite = {},
                            onSignIn = {}, onSignOut = {}, onToggle = {}, onPlay = {}, onPause = {}, onEntry = {},
                            loadCurrentArtwork = { id -> requestedIds.add(id); valid },
                        )
                    }
                }
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("Now playing artwork image").fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnUiThread { currentId.value = "episode:two" }
        compose.waitUntil(10_000) { compose.runOnIdle { requestedIds.contains("episode:two") } }
        compose.onNodeWithTag("Now playing artwork image").assertExists()
        compose.runOnIdle {
            assertTrue(requestedIds.contains("episode:one"))
            assertTrue(requestedIds.contains("episode:two"))
        }
    }

    private fun bitmapBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        bitmap.recycle()
        return output.toByteArray()
    }
}
