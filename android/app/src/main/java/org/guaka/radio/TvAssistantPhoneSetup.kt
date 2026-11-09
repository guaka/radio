package org.guaka.radio

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.NetworkInterface

@Composable
internal fun TvAssistantPhoneSetup(local: Boolean, target: String, onDismiss: () -> Unit, onReceived: (AssistantPhoneDetails) -> Unit) {
    var url by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val receive by rememberUpdatedState(onReceived)
    LaunchedEffect(Unit) {
        val messages = Channel<AssistantPhoneDetails>(1)
        var session: AssistantPhoneSetup? = null
        try {
            withContext(Dispatchers.IO) {
                val address = NetworkInterface.getNetworkInterfaces().toList()
                    .filter { it.isUp && !it.isLoopback && !it.name.startsWith("tun") }
                    .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
                    .firstOrNull { it.isSiteLocalAddress }
                    ?: error("Connect the TV to Wi-Fi or Ethernet and try again.")
                session = AssistantPhoneSetup(address, local, target, { messages.trySend(it) })
            }
            url = session?.url
            val details = withTimeoutOrNull(300_000) { messages.receive() }
            if (details == null) { url = null; error = "Code expired. Close and start again." }
            else receive(details)
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Could not start phone setup. Connect the TV to Wi-Fi or Ethernet, or use manual setup." }
        finally { session?.close(); messages.close() }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Set up with phone") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Scan with a phone on the same trusted Wi-Fi. Paste your Music Assistant token there, then select Connect and save on this TV.")
            url?.let { link ->
                val bitmap = remember(link) {
                    val matrix = MultiFormatWriter().encode(link, BarcodeFormat.QR_CODE, 320, 320)
                    Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888).apply {
                        setPixels(IntArray(320 * 320) { i -> if (matrix[i % 320, i / 320]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }, 0, 320, 0, 0, 320, 320)
                    }.asImageBitmap()
                }
                Image(bitmap, "Scan to set up Music Assistant", Modifier.size(200.dp))
                Text("Expires in five minutes. Local HTTP connection.")
            }
            Text("Can't open the link? Use the same Wi-Fi, check VPN or guest-network settings, and keep http:// (not https://). A computer on this network also works.")
            error?.let { Text(it) }
        }
    }, confirmButton = { TvButton("Close", onClick = onDismiss) })
}
