package org.guaka.radio

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.*
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PlexBrowseUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun shuffleControlsStartLibraryAlbumsAndStopActiveMode() {
        var scope: String? = null
        var stopped = false
        compose.setContent { MaterialTheme {
            AlbumShuffleControls("library:s:1", true, true, { scope = it }, { stopped = true })
        } }
        compose.onNodeWithText("Shuffle albums").performClick()
        assertEquals("catalog:s:1:albums", scope)
        compose.onNodeWithText("Stop album shuffle").performClick()
        assertTrue(stopped)
    }

    @Test fun radioHasNoShuffleActionAndDisabledControlCannotStart() {
        compose.setContent { MaterialTheme { AlbumShuffleControls("radio", false, false, {}, {}) } }
        compose.onNodeWithText("Shuffle albums").assertDoesNotExist()
    }

    @Test fun albumFavoritePersistsAndSignOutRemovesIt() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = ListeningStore(context)
        val album = "album:shuffle-ui-test:42"
        val radio = "radio:shuffle-ui-test"
        try {
            if (store.isFavorite(album)) store.toggleFavorite(album)
            if (!store.isFavorite(radio)) store.toggleFavorite(radio)
            assertTrue(store.toggleFavorite(album))
            assertTrue(ListeningStore(context).isFavorite(album))
            store.clearPlex()
            assertFalse(store.isFavorite(album))
            assertTrue(store.isFavorite(radio))
        } finally {
            if (store.isFavorite(album)) store.toggleFavorite(album)
            if (store.isFavorite(radio)) store.toggleFavorite(radio)
        }
    }
}
