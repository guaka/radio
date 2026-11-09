package org.guaka.radio

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test

class TvAssistantPhoneSetupUiTest {
    @get:Rule val compose = createAndroidComposeRule<TvActivity>()

    @Test fun setupDialogShowsPhoneNetworkTroubleshooting() {
        compose.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Surface {
                        TvAssistantPhoneSetup(local = true, target = "", onDismiss = {}, onReceived = {})
                    }
                }
            }
        }

        compose.onNodeWithText(
            "Can't open the link? Use the same Wi-Fi, check VPN or guest-network settings, and keep http:// (not https://). A computer on this network also works.",
            substring = true,
        ).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Close").assertIsDisplayed()
    }
}
