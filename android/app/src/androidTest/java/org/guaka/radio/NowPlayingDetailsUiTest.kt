package org.guaka.radio

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.junit4.createComposeRule
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
}
