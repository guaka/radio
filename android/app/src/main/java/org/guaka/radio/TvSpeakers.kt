package org.guaka.radio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
internal fun TvAssistantSettings(model: MainModel) {
    var choice by remember(model) { mutableStateOf(AssistantConnectionChoice.saved(model.repository.assistantConnectionMode())) }
    var remoteId by remember { mutableStateOf(model.repository.assistantRemoteId()) }
    var localUrl by remember { mutableStateOf(model.repository.assistantLocalUrl()) }
    var localToken by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    var phoneDetailsReceived by remember { mutableStateOf(false) }
    Text("Music Assistant", style = MaterialTheme.typography.titleLarge)
    var phoneSetup by remember { mutableStateOf(false) }
    TvButton("Set up with phone", enabled = !model.busy, onClick = { phoneSetup = true })
    if (phoneSetup) TvAssistantPhoneSetup(
        local = choice == AssistantConnectionChoice.LOCAL,
        target = if (choice == AssistantConnectionChoice.LOCAL) localUrl else remoteId,
        onDismiss = { phoneSetup = false },
        onReceived = { details ->
            if (choice == AssistantConnectionChoice.LOCAL) { localUrl = details.target; localToken = details.token }
            else { remoteId = details.target; token = details.token }
            saved = false
            phoneDetailsReceived = true
            phoneSetup = false
        },
    )
    TvButton("Local network${if (choice == AssistantConnectionChoice.LOCAL) " ✓" else ""}", enabled = !model.busy,
        onClick = { choice = AssistantConnectionChoice.LOCAL; saved = false })
    TvButton("Remote access${if (choice == AssistantConnectionChoice.REMOTE) " ✓" else ""}", enabled = !model.busy,
        onClick = { choice = AssistantConnectionChoice.REMOTE; saved = false })
    if (choice == AssistantConnectionChoice.LOCAL) {
        LocalAssistantFields(
            url = localUrl, onUrlChange = { localUrl = it; saved = false },
            token = localToken, onTokenChange = { localToken = it; saved = false },
            tokenSavedForUrl = model.repository.hasLocalAssistantToken(localUrl), enabled = !model.busy, isTv = true,
        )
        if (model.repository.assistantLocalServerId().isNotBlank()) {
            Text("A local server connection is saved on this device.")
            TvButton("Forget local connection", enabled = !model.busy, onClick = {
                model.disconnectLocalAssistant()
                localUrl = ""
                localToken = ""
                saved = false
            })
        }
    } else {
        Text("Enable Settings → Remote Access in Music Assistant and copy its Remote ID. Create an access token in Settings → Profile.")
        OutlinedTextField(remoteId, { remoteId = it; saved = false }, label = { Text("Remote ID") }, singleLine = true, enabled = !model.busy)
        OutlinedTextField(token, { token = it; saved = false }, label = { Text("Access token") }, singleLine = true,
            enabled = !model.busy, visualTransformation = PasswordVisualTransformation())
        if (model.repository.hasAssistantToken(remoteId)) Text("Token saved securely. Leave blank to reuse it.")
    }
    if (phoneDetailsReceived && !saved) Text("Details received from your phone. Select Connect and save to finish.")
    TvButton("Connect and save", enabled = !model.busy, onClick = {
        saved = false
        if (choice == AssistantConnectionChoice.LOCAL) {
            model.saveLocalAssistant(localUrl, localToken) { localToken = ""; saved = true }
        } else {
            model.saveAssistant(remoteId, token) { token = ""; saved = true }
        }
    })
    if (saved) Text("Connected. Settings saved.") else if (model.status.isNotBlank()) Text(model.status)
    Text("Speaker audio", style = MaterialTheme.typography.titleMedium)
    TvButton("Relay through this TV${if (model.speakerSourceMode == SpeakerSourceMode.PHONE_RELAY) " ✓" else ""}",
        onClick = { model.setSpeakerSourceMode(SpeakerSourceMode.PHONE_RELAY) })
    Text("Keep this TV connected. Music Assistant must be able to reach it over your LAN or Nebula network.")
    TvButton("Music Assistant sources${if (model.speakerSourceMode == SpeakerSourceMode.MUSIC_ASSISTANT) " ✓" else ""}",
        onClick = { model.setSpeakerSourceMode(SpeakerSourceMode.MUSIC_ASSISTANT) })
    Text("Uses Music Assistant library matching for Plex and direct stream URLs for YouTube.")
}

/** Buttons, rather than touch sliders, keep every speaker action available to a D-pad. */
@Composable
internal fun TvSpeakerDialog(
    speakers: List<Speaker>?, selected: Set<String>, volumes: List<SpeakerVolume>,
    busy: Boolean, status: String, progress: SpeakerProgress?,
    onDismiss: () -> Unit, onSelect: (List<Speaker>) -> Unit, onLocal: () -> Unit,
    onVolume: (String, Int) -> Unit, onSeek: (Long) -> Unit,
) {
    var chosen by remember(selected) { mutableStateOf(selected) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Playback output") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TvButton("This TV", enabled = !busy, onClick = onLocal)
                Text("Select speakers. Multiple speakers use Music Assistant synchronization.")
                if (busy) Text("Updating speakers…")
                if (status.isNotBlank()) Text(status)
                if (speakers.isNullOrEmpty() && !busy) Text("No speakers available. Check Music Assistant in Settings.")
                speakers.orEmpty().forEach { speaker ->
                    TvButton("${if (speaker.id in chosen) "✓ " else ""}${speaker.name}", enabled = !busy,
                        description = "${if (speaker.id in chosen) "Deselect" else "Select"} ${speaker.name}", onClick = {
                            chosen = if (speaker.id in chosen) chosen - speaker.id else chosen + speaker.id
                        })
                }
                if (volumes.isNotEmpty()) {
                    HorizontalDivider()
                    Text("Speaker volume")
                    volumes.forEach { volume ->
                        Text("${volume.speaker.name}: ${volume.level?.let { "$it%" } ?: "Volume unavailable"}")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TvButton("−", enabled = !busy && volume.level != null && volume.level > 0,
                                description = "Lower volume ${volume.speaker.name}", onClick = {
                                    volume.level?.let { onVolume(volume.speaker.id, (it - 5).coerceAtLeast(0)) }
                                })
                            TvButton("+", enabled = !busy && volume.level != null && volume.level < 100,
                                description = "Raise volume ${volume.speaker.name}", onClick = {
                                    volume.level?.let { onVolume(volume.speaker.id, (it + 5).coerceAtMost(100)) }
                                })
                        }
                    }
                }
                if (progress != null && progress.durationMs > 0) {
                    Text("Track position")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TvButton("−30 sec", enabled = !busy, onClick = {
                            onSeek((progress.positionAt(System.nanoTime() / 1_000_000) - 30_000).coerceAtLeast(0))
                        })
                        TvButton("+30 sec", enabled = !busy, onClick = {
                            onSeek((progress.positionAt(System.nanoTime() / 1_000_000) + 30_000).coerceAtMost(progress.durationMs))
                        })
                    }
                }
            }
        },
        confirmButton = {
            TvButton("Play on speakers", enabled = !busy && speakers.orEmpty().any { it.id in chosen },
                onClick = { onSelect(speakers.orEmpty().filter { it.id in chosen }) })
        }, dismissButton = { TvButton("Done", onClick = onDismiss) },
    )
}
