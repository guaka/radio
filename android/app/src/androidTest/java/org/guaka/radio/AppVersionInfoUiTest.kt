package org.guaka.radio

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppVersionInfoUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun displaysGeneratedVersionAndBuildInformation() {
        compose.setContent {
            MaterialTheme { AppVersionInfo() }
        }

        compose.onNodeWithText("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})").assertIsDisplayed()
        compose.onNodeWithText("Build: ${BuildConfig.BUILD_TIME} (Lisbon)").assertIsDisplayed()
    }
}
