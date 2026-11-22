package org.guaka.radio

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip19Bech32.toNpub
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
internal fun NostrConnections(nostr: NostrFavoritesController, bridge: AndroidSignerBridge) {
    var bunker by remember { mutableStateOf("") }
    var historyRelay by remember(nostr.historyRelay) { mutableStateOf(nostr.historyRelay) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Nostr favorites", style = MaterialTheme.typography.titleMedium)
        Text("Share radio stars publicly with Radiostr. Your signer keeps your private key. Plex favorites stay on this phone.")
        if (nostr.pubkey.isBlank()) {
            TextButton(enabled = !nostr.busy, onClick = { nostr.connectAndroid(bridge) }) {
                Text("Connect Android signer (NIP-55)")
            }
            OutlinedTextField(bunker, { bunker = it }, label = { Text("Remote signer bunker URL") },
                singleLine = true, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())
            TextButton(enabled = !nostr.busy && bunker.isNotBlank(), onClick = { nostr.connectRemote(bunker); bunker = "" }) {
                Text("Connect remote signer (NIP-46)")
            }
        } else {
            val npub = remember(nostr.pubkey) { nostr.pubkey.hexToByteArray().toNpub() }
            SelectionContainer { Text("Connected: $npub", modifier = Modifier.fillMaxWidth(), softWrap = true) }
            Row {
                TextButton(enabled = !nostr.busy, onClick = nostr::sync) { Text("Sync favorites") }
                TextButton(onClick = nostr::disconnect) { Text("Disconnect") }
            }
        }
        if (nostr.pubkey.isNotBlank()) {
            Text("Listening history", style = MaterialTheme.typography.titleMedium)
            Text("Radio and YouTube history is encrypted for your Nostr account. Use the same relay on each device. Relays may keep older encrypted copies.")
            OutlinedTextField(historyRelay, { historyRelay = it }, label = { Text("History relay URL") },
                singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !nostr.historyEnabled && !nostr.historyBusy)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Sync listening history", modifier = Modifier.weight(1f))
                Switch(checked = nostr.historyEnabled,
                    enabled = !nostr.historyBusy && (nostr.historyEnabled || historyRelay.isNotBlank()),
                    onCheckedChange = { if (it) nostr.configureHistory(historyRelay) else nostr.disableHistory() },
                    modifier = Modifier.semantics { contentDescription = "Sync listening history" })
            }
            if (nostr.historyEnabled) TextButton(enabled = !nostr.historyBusy, onClick = nostr::syncHistory) {
                Text("Retry history sync")
            }
            if (nostr.historyStatus.isNotBlank()) Text(nostr.historyStatus, style = MaterialTheme.typography.bodySmall)
        }
        if (nostr.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            if (nostr.pubkey.isBlank()) TextButton(onClick = nostr::disconnect) { Text("Cancel connection") }
        }
        if (nostr.status.isNotBlank()) Text(nostr.status, style = MaterialTheme.typography.bodySmall)
    }
}
