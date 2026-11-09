package org.guaka.radio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class LocalAssistantSettingsUiTest {
    @get:Rule val compose = createComposeRule()
    private val viewModelStore = ViewModelStore()

    @After fun clearViewModels() { viewModelStore.clear() }

    private fun show(model: MainModel) {
        val remoteId = mutableStateOf("")
        val remoteToken = mutableStateOf("")
        val saveAttempted = mutableStateOf(false)
        val close = mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(400.dp, 700.dp)) {
                    SettingsPane(
                        model = model,
                        assistantRemoteId = remoteId.value,
                        onAssistantRemoteIdChange = { remoteId.value = it },
                        assistantToken = remoteToken.value,
                        onAssistantTokenChange = { remoteToken.value = it },
                        saveAttempted = saveAttempted.value,
                        onClose = { close.value = true }, onSaveStarted = { saveAttempted.value = true }, onSaved = {},
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun localModeShowsHiddenTokenAndSwitchingModesRetainsDraftValues() {
        val model = MainModel(ApplicationProvider.getApplicationContext<RadioApp>())
        viewModelStore.put("local-settings-model", model)
        show(model)

        compose.onNodeWithText("Local network").performScrollTo().performClick()
        compose.onNodeWithText("Server URL").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Remote ID").assertDoesNotExist()
        compose.onNodeWithText("Server URL").performTextInput("http://music-assistant.local:8095")
        compose.onNodeWithText("Access token").performScrollTo().performTextInput("example-secret-token")
        val tokenNode = compose.onNode(hasText("Access token") and hasSetTextAction()).fetchSemanticsNode()
        assertTrue("local token field should have password semantics",
            tokenNode.config.contains(SemanticsProperties.Password))

        compose.onNodeWithText("Remote access").performScrollTo().performClick()
        compose.onNodeWithText("Remote ID").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Server URL").assertDoesNotExist()
        compose.onNodeWithText("Local network").performScrollTo().performClick()
        compose.onNodeWithText("Server URL").performScrollTo().assertTextContains("http://music-assistant.local:8095")
        compose.onNodeWithText("Server URL").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            .performKeyInput { pressKey(Key.Tab) }
        compose.onNodeWithText("Access token").assertIsFocused()
    }

    @Test fun invalidLocalServerShowsConnectionError() {
        val model = MainModel(ApplicationProvider.getApplicationContext<RadioApp>())
        viewModelStore.put("local-settings-error-model", model)
        show(model)
        compose.onNodeWithText("Local network").performScrollTo().performClick()
        compose.onNodeWithText("Server URL").performScrollTo().performTextInput("not a server url")
        compose.onNodeWithText("Connect and save").performClick()
        compose.waitUntil(10_000) { model.status.isNotBlank() && !model.busy }
        compose.onNodeWithText(model.status).performScrollTo().assertIsDisplayed()
    }

    @Test fun discoveredServerCanBeSelectedWithoutReusingAnotherServersToken() {
        val url = mutableStateOf("http://old-server.local:8095")
        val token = mutableStateOf("old-server-token")
        compose.setContent {
            MaterialTheme {
                LocalAssistantFields(
                    url = url.value, onUrlChange = { url.value = it }, token = token.value,
                    onTokenChange = { token.value = it }, tokenSavedForUrl = false,
                    discover = { listOf(DiscoveredAssistant("Kitchen", "http://192.168.1.44:8095", "kitchen-id")) },
                )
            }
        }
        compose.onNodeWithText("Find local servers").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Kitchen — http://192.168.1.44:8095").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Kitchen — http://192.168.1.44:8095").performClick()
        compose.runOnIdle {
            assertEquals("http://192.168.1.44:8095", url.value)
            assertEquals("", token.value)
        }
    }
}

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class TvLocalAssistantSettingsUiTest {
    @get:Rule val compose = createComposeRule()
    private val viewModelStore = ViewModelStore()
    private var model: MainModel? = null

    @After fun clearViewModels() {
        model?.disconnectLocalAssistant()
        viewModelStore.clear()
    }

    @Test fun tvLocalFormSupportsDpadModeAndFieldNavigationAndShowsInvalidSetupError() {
        val active = MainModel(ApplicationProvider.getApplicationContext<RadioApp>())
        model = active
        active.disconnectLocalAssistant()
        viewModelStore.put("tv-local-assistant-settings-model", active)
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    Column(Modifier.size(520.dp, 760.dp).verticalScroll(rememberScrollState())) {
                        TvAssistantSettings(active)
                    }
                }
            }
        }

        compose.onNodeWithText("Local network", substring = true)
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithText("Find local servers").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            .assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Server URL").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Access token").assertIsFocused()
        compose.onNodeWithText("Access token").performTextInput("must-not-be-saved")
        val passwordNode = compose.onNode(hasText("Access token") and hasSetTextAction()).fetchSemanticsNode()
        assertTrue(passwordNode.config.contains(SemanticsProperties.Password))

        compose.onNodeWithText("Remote access", substring = true)
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithText("Remote ID").assertIsDisplayed()
        compose.onNodeWithText("Server URL").assertDoesNotExist()
        compose.onNodeWithText("Local network", substring = true)
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithText("Server URL").performTextInput("not a server url")
        compose.onNodeWithText("Connect and save").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            .performKeyInput { pressKey(Key.DirectionCenter) }

        compose.waitUntil(10_000) { active.status.isNotBlank() && !active.busy }
        compose.onNodeWithText(active.status).performScrollTo().assertIsDisplayed()
        assertTrue(active.repository.assistantLocalServerId().isBlank())
        assertTrue(!active.repository.hasLocalAssistantToken("not a server url"))
    }
}
