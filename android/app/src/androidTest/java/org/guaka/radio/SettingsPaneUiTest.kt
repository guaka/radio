package org.guaka.radio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsPaneUiTest {
    @get:Rule val compose = createComposeRule()
    private val viewModelStore = ViewModelStore()

    @After fun clearViewModels() {
        viewModelStore.clear()
    }

    @Test fun formScrollsWithoutLosingValuesAndFooterAndCloseRemainAvailable() {
        val app = ApplicationProvider.getApplicationContext<RadioApp>()
        val model = MainModel(app)
        viewModelStore.put("settings-test-model", model)
        val remoteId = mutableStateOf("")
        val token = mutableStateOf("")
        val closed = mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                // Keep the form scrollable even when this test runs on a large device.
                Box(Modifier.size(width = 360.dp, height = 480.dp)) {
                    SettingsPane(
                        model = model,
                        assistantRemoteId = remoteId.value,
                        onAssistantRemoteIdChange = { remoteId.value = it },
                        assistantToken = token.value,
                        onAssistantTokenChange = { token.value = it },
                        saveAttempted = false,
                        onClose = { closed.value = true },
                        onSaveStarted = {},
                        onSaved = {},
                    )
                }
            }
        }
        compose.onNodeWithText("Plex").assertIsDisplayed()
        compose.onNodeWithText("Connect and save").assertIsDisplayed()
        compose.onNodeWithText("Nostr favorites").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Music Assistant").performScrollTo().assertIsDisplayed()

        compose.onNodeWithText("Remote access").performScrollTo().performClick()
        compose.onNodeWithText("Remote ID").performScrollTo()
        compose.onNodeWithText("Remote ID").performTextInput("remote-id-example")
        compose.onNodeWithText("Connect and save").assertIsDisplayed()
        compose.onNodeWithText("Access token").performScrollTo()
        compose.onNodeWithText("Access token").performTextInput("secret-example")
        compose.runOnIdle {
            assertEquals("remote-id-example", remoteId.value)
            assertEquals("secret-example", token.value)
        }
        compose.onNodeWithText("Build:", substring = true).performScrollTo()
        compose.onNodeWithText("Connect and save").assertIsDisplayed()
        compose.onNodeWithText("Remote ID").performScrollTo()
        compose.onNodeWithText("Remote ID").assertIsDisplayed().assertTextContains("remote-id-example")
        compose.onNodeWithText("Access token").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals("secret-example", token.value) }
        compose.onNodeWithText("Connect and save").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.runOnIdle { assertTrue(closed.value) }
    }
}
