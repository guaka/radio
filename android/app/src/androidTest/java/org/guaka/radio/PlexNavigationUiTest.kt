@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package org.guaka.radio

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PlexNavigationUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun enteringAlbumShowsFavoriteAndBackRestoresLoadedResultsAndViewport() {
        lateinit var model: MainModel
        lateinit var previous: List<String>
        compose.runOnIdle {
            model = ViewModelProvider(compose.activity)[MainModel::class.java]
            previous = model.repository.listening.favoriteIds()
            model.repository.listening.replaceFavorites((0 until 80).map { "album:navigation-test:$it" })
            model.browse("favorites")
        }
        try {
            compose.waitUntil(10_000) { !model.busy && model.entries.size >= 50 }
            compose.onNode(hasScrollToIndexAction()).performScrollToIndex(35)
            // LazyListState and the saved browse position are observed separately. Let the
            // snapshotFlow collector publish the scroll position before capturing it.
            compose.waitUntil(5_000) { model.browseIndex >= 35 }
            var index = 0
            var offset = 0
            var count = 0
            compose.runOnIdle {
                index = model.browseIndex; offset = model.browseOffset; count = model.entries.size
                assertTrue(index > 0)
                model.browse("album:navigation-test:35", title = "Test album")
            }
            compose.onNodeWithText("Test album").assertIsDisplayed()
            compose.onNodeWithContentDescription("Remove favorite Test album").assertIsDisplayed()
            compose.onNodeWithContentDescription("Remove favorite Test album").performClick()
            compose.waitUntil(5_000) { "album:navigation-test:35" !in model.favorites }
            compose.onNodeWithContentDescription("Favorite Test album").assertIsDisplayed()
            compose.onNodeWithContentDescription("Favorite Test album").performClick()
            compose.waitUntil(5_000) { "album:navigation-test:35" in model.favorites }
            compose.onNodeWithContentDescription("Remove favorite Test album").assertIsDisplayed()
            compose.onNodeWithText("‹ Favorites").performClick()
            compose.waitUntil(10_000) { model.parent == "favorites" && !model.busy }
            compose.runOnIdle {
                assertEquals("", model.searchTerm)
                assertEquals(count, model.entries.size)
                assertEquals(index, model.browseIndex)
                assertEquals(offset, model.browseOffset)
            }
        } finally {
            compose.runOnIdle { model.repository.listening.replaceFavorites(previous) }
        }
    }
}
