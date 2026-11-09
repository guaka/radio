package org.guaka.radio

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import org.junit.Rule
import org.junit.Test

class NowPlayingDetailsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun stationRemainsVisibleAlongsideLongArtist() {
        compose.setContent {
            MaterialTheme { Column { NowPlayingDetails("Artist ".repeat(100), "Groove Salad", "Song") } }
        }
        compose.onNodeWithText("Groove Salad").assertIsDisplayed()
        compose.onNodeWithText("Artist ".repeat(100)).assertIsDisplayed()
    }

    @Test fun stationIsNotRepeatedWhenItIsAlreadyTheTitle() {
        compose.setContent { MaterialTheme { NowPlayingDetails(null, "Groove Salad", "Groove Salad") } }
        compose.onNodeWithText("Groove Salad").assertDoesNotExist()
    }

    @Test fun mediaArtworkLoaderRefreshesWhenTheNowPlayingIdChanges() {
        var mediaId by mutableStateOf("track:server:1")
        val requested = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                ResolvedMediaArtwork(
                    mediaId = mediaId,
                    loadEntry = { id -> requested += id; Entry(id, "Cover for $id", artwork = "thumb") },
                    content = { androidx.compose.material3.Text("Artwork: ${it.title}") },
                    fallback = { androidx.compose.material3.Text("Artwork unavailable") },
                )
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Artwork: Cover for track:server:1")).fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { mediaId = "radio:groovesalad" }
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Artwork: Cover for radio:groovesalad")).fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { org.junit.Assert.assertEquals(listOf("track:server:1", "radio:groovesalad"), requested) }
    }
}
