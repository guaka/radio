package org.guaka.radio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
internal fun SettingsPane(
    model: MainModel,
    assistantRemoteId: String,
    onAssistantRemoteIdChange: (String) -> Unit,
    assistantToken: String,
    onAssistantTokenChange: (String) -> Unit,
    saveAttempted: Boolean,
    onClose: () -> Unit,
    onSaveStarted: () -> Unit,
    onSaved: () -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("‹  Back") }
            Spacer(Modifier.width(8.dp))
            Icon(painterResource(R.drawable.ic_settings), contentDescription = null, modifier = Modifier.semantics {
                contentDescription = "Setup"
                selected = true
            })
            Spacer(Modifier.width(8.dp))
            Text("Connections", style = MaterialTheme.typography.titleLarge)
        }

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Build: ${BuildConfig.BUILD_TIME} (Lisbon)", style = MaterialTheme.typography.bodySmall)

            SettingsSection(title = "Plex") {
                Text("Plex works away from home when your server's Remote Access is configured.")
                if (!model.plexSignedIn) {
                    TextButton(onClick = { model.signIn(); onClose() }) { Text("Sign in to Plex") }
                } else {
                    Text(if (model.plexAccountName.isBlank()) "Signed in to Plex" else "Plex: ${model.plexAccountName}")
                    TextButton(onClick = model::signOut) { Text("Sign out of Plex") }
                }
            }

            SettingsSection(title = null) {
                NostrConnections(model.nostr, model.nostrBridge)
            }

            SettingsSection(title = "Music Assistant") {
                Text("Connect a Music Assistant server to play stations on its speakers.")
                OutlinedTextField(
                    value = assistantRemoteId,
                    onValueChange = onAssistantRemoteIdChange,
                    label = { Text("Remote ID") },
                    placeholder = { Text("From Music Assistant → Settings → Remote Access") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    value = assistantToken,
                    onValueChange = onAssistantTokenChange,
                    label = { Text("Access token") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
                )
                if (model.repository.hasAssistantToken(assistantRemoteId)) {
                    Text("Token saved securely. Leave blank to use it, or enter a replacement.", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "Enable Music Assistant → Settings → Remote Access and copy its Remote ID. Create an access token in Settings → Profile.",
                    style = MaterialTheme.typography.bodySmall,
                )
                HorizontalDivider()
                Text("Speaker audio", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = model.speakerSourceMode == SpeakerSourceMode.PHONE_RELAY,
                        onClick = { model.setSpeakerSourceMode(SpeakerSourceMode.PHONE_RELAY) })
                    Text("Relay through this phone (default)")
                }
                Text("YouTube and Plex audio is fetched by this phone. Keep Nebula connected, or use a LAN where Music Assistant can reach the phone. No extra music providers are needed.")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = model.speakerSourceMode == SpeakerSourceMode.MUSIC_ASSISTANT,
                        onClick = { model.setSpeakerSourceMode(SpeakerSourceMode.MUSIC_ASSISTANT) })
                    Text("Music Assistant sources / direct URLs")
                }
                Text("Uses Music Assistant library matching for Plex and direct stream URLs for YouTube. Changes apply to the next speaker selection.")
                if (saveAttempted && model.status.isNotBlank()) {
                    Text(model.status, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onClose) { Text("Close") }
            Spacer(Modifier.width(8.dp))
            Button(enabled = !model.busy, onClick = {
                onSaveStarted()
                model.saveAssistant(assistantRemoteId, assistantToken, onSaved)
            }) {
                Text(if (model.busy) "Checking connection…" else "Connect and save")
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String?, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = {
                if (title != null) Text(title, style = MaterialTheme.typography.titleMedium)
                content()
            },
        )
    }
}
