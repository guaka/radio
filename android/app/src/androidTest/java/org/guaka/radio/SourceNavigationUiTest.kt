package org.guaka.radio

import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class SourceNavigationUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun sourceIconsExposeSelectionWhileSwitchingSources() {
        val radio = compose.onNodeWithContentDescription("Radio")
        val plex = compose.onNodeWithContentDescription("Plex")
        val youtube = compose.onNodeWithContentDescription("YouTube")

        radio.assertIsSelected()
        radio.performClick().assertIsSelected()
        plex.assertIsNotSelected()
        plex.performClick().assertIsSelected()
        youtube.performClick().assertIsSelected()
        plex.assertIsNotSelected()
    }
}
