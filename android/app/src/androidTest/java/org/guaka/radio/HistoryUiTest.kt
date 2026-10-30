package org.guaka.radio

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before fun seedHistory() {
        val store = (compose.activity.application as RadioApp).repository.listening
        store.clearHistory()
        store.recordPlayed("radio:7soul")
    }

    @After fun clearHistory() {
        (compose.activity.application as RadioApp).repository.listening.clearHistory()
    }

    @Test fun historyDisplaysPlayedStationAndCanBeCleared() {
        compose.onNodeWithText("History").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("7soul")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("7soul").assertIsDisplayed()
        compose.onNodeWithText("Clear history").performClick()
        compose.waitUntil(10_000) {
            (compose.activity.application as RadioApp).repository.listening.historyIds().isEmpty()
        }
        compose.onNodeWithText("No items found.").assertIsDisplayed()
    }
}
