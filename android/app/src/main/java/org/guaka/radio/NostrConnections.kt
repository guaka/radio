package org.guaka.radio

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
internal fun NostrConnections(nostr: NostrFavoritesController, bridge: AndroidSignerBridge) {
    var bunker by remember { mutableStateOf("") }
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
            Text("Connected: ${nostr.pubkey.take(12)}…${nostr.pubkey.takeLast(8)}")
            Row {
                TextButton(enabled = !nostr.busy, onClick = nostr::sync) { Text("Sync favorites") }
                TextButton(onClick = nostr::disconnect) { Text("Disconnect") }
            }
        }
        if (nostr.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            if (nostr.pubkey.isBlank()) TextButton(onClick = nostr::disconnect) { Text("Cancel connection") }
        }
        if (nostr.status.isNotBlank()) Text(nostr.status, style = MaterialTheme.typography.bodySmall)
    }
}
