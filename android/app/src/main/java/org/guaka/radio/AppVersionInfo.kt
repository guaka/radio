package org.guaka.radio

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
internal fun AppVersionInfo() {
    Text("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodySmall)
    Text("Build: ${BuildConfig.BUILD_TIME} (Lisbon)", style = MaterialTheme.typography.bodySmall)
}
