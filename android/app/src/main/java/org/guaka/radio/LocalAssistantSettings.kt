package org.guaka.radio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException

internal enum class AssistantConnectionChoice(val preference: String) {
    LOCAL("local"), REMOTE("remote");

    companion object {
        fun saved(value: String) = if (value == REMOTE.preference) REMOTE else LOCAL
    }
}

@Composable
internal fun AssistantConnectionModeSelector(
    selected: AssistantConnectionChoice,
    enabled: Boolean = true,
    onSelected: (AssistantConnectionChoice) -> Unit,
) {
    Text("Connection", style = MaterialTheme.typography.titleSmall)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected == AssistantConnectionChoice.LOCAL, enabled = enabled,
            onClick = { onSelected(AssistantConnectionChoice.LOCAL) })
        Text("Local network", modifier = Modifier.weight(1f).clickable(enabled = enabled) {
            onSelected(AssistantConnectionChoice.LOCAL)
        })
        RadioButton(selected == AssistantConnectionChoice.REMOTE, enabled = enabled,
            onClick = { onSelected(AssistantConnectionChoice.REMOTE) })
        Text("Remote access", modifier = Modifier.clickable(enabled = enabled) {
            onSelected(AssistantConnectionChoice.REMOTE)
        })
    }
}

@Composable
internal fun LocalAssistantFields(
    url: String,
    onUrlChange: (String) -> Unit,
    token: String,
    onTokenChange: (String) -> Unit,
    tokenSavedForUrl: Boolean,
    enabled: Boolean = true,
    isTv: Boolean = false,
    discover: suspend (android.content.Context) -> List<DiscoveredAssistant> = ::discoverLocalAssistants,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val tvDpadFocusModifier = if (isTv) Modifier.onPreviewKeyEvent { event ->
        val direction = when (event.key) {
            Key.DirectionDown -> FocusDirection.Down
            Key.DirectionUp -> FocusDirection.Up
            else -> null
        }
        if (direction == null) false else {
            if (event.type == KeyEventType.KeyDown) focusManager.moveFocus(direction)
            true
        }
    } else Modifier
    fun selectUrl(next: String) {
        if (url != next) onTokenChange("")
        onUrlChange(next)
    }
    var searching by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var discoveryFinished by remember { mutableStateOf(false) }
    var discovered by remember { mutableStateOf<List<DiscoveredAssistant>>(emptyList()) }
    LaunchedEffect(searching) {
        if (searching) {
            loading = true
            discoveryFinished = true
            discovered = try { discover(context) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { emptyList() }
            loading = false
            searching = false
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Connect directly to Music Assistant on your home network. Remote Access is not required.")
        if (isTv) {
            TvButton(if (loading) "Searching for local servers…" else "Find local servers", enabled = enabled,
                description = "Find local servers", onClick = { if (!loading) searching = true })
        } else {
            Button(onClick = { if (!loading) searching = true }, enabled = enabled) {
                Text(if (loading) "Finding local servers…" else "Find local servers")
            }
        }
        discovered.forEach { server ->
            if (isTv) {
                TvButton("${server.name} — ${server.url}", enabled = enabled,
                    description = "Use ${server.name} at ${server.url}", onClick = {
                        selectUrl(server.url)
                    })
            } else {
                Button(onClick = { selectUrl(server.url) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text("${server.name} — ${server.url}")
                }
            }
        }
        if (discoveryFinished && discovered.isEmpty() && !loading) {
            Text("No servers found. Enter the server URL manually.", style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(
            value = url,
            onValueChange = ::selectUrl,
            label = { Text("Server URL") },
            placeholder = { Text("http://homeassistant.local:8095") },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().then(tvDpadFocusModifier),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
        )
        OutlinedTextField(
            value = token,
            onValueChange = onTokenChange,
            label = { Text("Access token") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().then(tvDpadFocusModifier),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        )
        if (tokenSavedForUrl) {
            Text("Token saved securely for this server. Leave blank to reuse it, or enter a replacement.",
                style = MaterialTheme.typography.bodySmall)
        } else {
            Text("Create an access token in Music Assistant → Settings → Profile. Your Home Assistant login is not shared automatically.",
                style = MaterialTheme.typography.bodySmall)
        }
        Text("HTTP sends the token over your local network; use a trusted network.", style = MaterialTheme.typography.bodySmall)
    }
}
