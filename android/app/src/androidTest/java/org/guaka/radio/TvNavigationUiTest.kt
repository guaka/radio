package org.guaka.radio

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.input.key.Key
import androidx.compose.runtime.mutableStateOf
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
    private var backs = 0

    private fun showContent(status: String = "", trackMetadata: Boolean = false, canGoBack: Boolean = false) {
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
                            hasMore = false, canGoBack = canGoBack, listEpoch = 0, search = "", signedIn = false,
                            onSearch = {}, onSource = { source = it }, onBack = { backs++ }, onMore = {},
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

    @Test fun backIconIsBesideHeadingAndWorksWithRemote() {
        showContent(canGoBack = true)
        val back = compose.onNodeWithContentDescription("Back")
        back.assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithText("Back").assertDoesNotExist()
        val backBounds = back.fetchSemanticsNode().boundsInRoot
        val heading = compose.onNodeWithTag("TV content heading").fetchSemanticsNode().boundsInRoot
        assertTrue(backBounds.right <= heading.left)
        assertEquals(heading.center.y, backBounds.center.y, 1f)
        back.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            .assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1, backs) }
        back.performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithContentDescription("Settings").assertIsFocused()
    }

    @Test fun backIconIsHiddenAtSourceRoot() {
        showContent()
        compose.onNodeWithContentDescription("Back").assertDoesNotExist()
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
        compose.onNodeWithContentDescription("Radio Guaka", useUnmergedTree = true).assertIsDisplayed()
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
        compose.onNodeWithContentDescription("Radio").assertIsFocused()
        compose.onNodeWithTag("TV row text Radio", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Radio Guaka", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Radio").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals("radio", source) }
        for ((label, id) in listOf("Plex" to "plex", "YouTube" to "youtube", "Favorites" to "favorites", "History" to "history")) {
            compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionDown) }
            compose.onNodeWithContentDescription(label).assertIsFocused()
            compose.onNodeWithTag("TV row icon $label", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithTag("TV row text $label", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithContentDescription(label).performKeyInput {
                pressKey(Key.DirectionCenter)
            }
            compose.runOnIdle { assertEquals(id, source) }
        }
        compose.onNodeWithContentDescription("Favorites").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            .performKeyInput { pressKey(Key.DirectionCenter) }
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

    @Test fun albumFavoritesCanBeStarredAndUnstarredFromRowsAndAlbumHeading() {
        val favorites = mutableStateOf(emptySet<String>())
        val album = Entry("album:server:42", "Test Album")
        compose.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Surface {
                        TvContent(
                            entries = listOf(album), title = "Albums", favorites = favorites.value,
                            currentTitle = "", currentId = null, playing = false, ready = true, busy = false,
                            status = "", hasMore = false, canGoBack = false, listEpoch = 0, search = "", signedIn = false,
                            onSearch = {}, onSource = {}, onBack = {}, onMore = {},
                            onFavorite = { id -> favorites.value = if (id in favorites.value) favorites.value - id else favorites.value + id },
                            onSignIn = {}, onSignOut = {}, onToggle = {}, onPlay = {}, onPause = {}, onEntry = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Favorite Test Album").performClick()
        compose.onNodeWithContentDescription("Remove Test Album from favorites").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Favorite Test Album").assertIsDisplayed()

        compose.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Surface {
                        TvContent(
                            entries = emptyList(), title = "Test Album", favorites = favorites.value,
                            currentTitle = "", currentId = null, playing = false, ready = true, busy = false,
                            status = "", hasMore = false, canGoBack = true, listEpoch = 1, search = "", signedIn = false,
                            locationId = album.id, onSearch = {}, onSource = {}, onBack = {}, onMore = {},
                            onFavorite = { id -> favorites.value = if (id in favorites.value) favorites.value - id else favorites.value + id },
                            onSignIn = {}, onSignOut = {}, onToggle = {}, onPlay = {}, onPause = {}, onEntry = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Favorite album Test Album").performClick()
        compose.onNodeWithContentDescription("Remove album Test Album from favorites").assertIsDisplayed()
    }

    @Test fun backRestoresTheSelectedArtistFocusAndViewport() {
        val parent = mutableStateOf("artists")
        val epoch = mutableStateOf(0)
        val artists = (0..59).map { Entry("artist:server:$it", "Artist $it") }
        compose.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Surface {
                        val child = parent.value != "artists"
                        TvContent(
                            entries = if (child) listOf(Entry("album:server:1", "Album")) else artists,
                            title = if (child) "Artist 15" else "Artists", favorites = emptySet(), currentTitle = "",
                            currentId = null, playing = false, ready = true, busy = false, status = "",
                            hasMore = false, canGoBack = child, listEpoch = epoch.value, search = "", signedIn = false,
                            locationId = parent.value, browseKey = parent.value, onSearch = {}, onSource = {}, onBack = {
                                parent.value = "artists"
                                epoch.value++
                            }, onMore = {}, onFavorite = {}, onSignIn = {}, onSignOut = {}, onToggle = {},
                            onPlay = {}, onPause = {}, onEntry = {
                                parent.value = "artist:server:15"
                                epoch.value++
                            },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Open Artist 0")
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        repeat(15) {
            compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionDown) }
            compose.waitForIdle()
        }
        compose.onNodeWithContentDescription("Open Artist 15").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Open Album").assertIsFocused()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Open Artist 15").assertIsDisplayed().assertIsFocused()
    }

    @Test fun tvAlbumShuffleUsesTheCurrentLibraryScopeAndCanStop() {
        val active = mutableStateOf(false)
        var started = ""
        var stopped = 0
        compose.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Surface {
                        TvContent(
                            entries = emptyList(), title = "Library", favorites = emptySet(), currentTitle = "",
                            currentId = null, playing = false, ready = true, busy = false, status = "",
                            hasMore = false, canGoBack = false, listEpoch = 0, search = "", signedIn = false,
                            locationId = "library:server:1", albumShuffleActive = active.value, albumShuffleEnabled = true,
                            onShuffleAlbums = { started = it; active.value = true }, onStopAlbumShuffle = { stopped++; active.value = false },
                            onSearch = {}, onSource = {}, onBack = {}, onMore = {}, onFavorite = {},
                            onSignIn = {}, onSignOut = {}, onToggle = {}, onPlay = {}, onPause = {}, onEntry = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Shuffle albums").performClick()
        compose.runOnIdle { assertEquals("catalog:server:1:albums", started) }
        compose.onNodeWithText("Stop album shuffle").performClick()
        compose.runOnIdle { assertEquals(1, stopped) }
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
