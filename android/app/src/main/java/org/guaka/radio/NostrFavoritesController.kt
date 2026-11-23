package org.guaka.radio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.net.URI

/** Explicit opt-in to public Radiostr favorites; Plex favorites never leave the phone. */
internal class NostrFavoritesController(private val repository: MusicRepository, private val scope: CoroutineScope) {
    private val credentials = repository.credentials
    private val relays = NostrRelays()
    private val mutex = Mutex()
    private var signer: NostrSigner? = null
    private var generation = 0L
    private var job: Job? = null
    private var operationToken = 0L
    var pubkey by mutableStateOf(""); private set
    var status by mutableStateOf(""); private set
    var busy by mutableStateOf(false); private set
    var authUrl by mutableStateOf<String?>(null); private set
    private var lastCreatedAt = 0L
    private var historyJob: Job? = null
    private var delayedHistorySync: Job? = null
    private var historyForeground = false
    private val stopHistoryObserver = repository.listening.observeLocalHistoryWrites {
        scope.launch {
            if (!historyForeground) return@launch
            delayedHistorySync?.cancel()
            delayedHistorySync = scope.launch {
                delay(1_200)
                syncHistory()
            }
        }
    }
    var historyRelay by mutableStateOf(""); private set
    var historyStatus by mutableStateOf(""); private set
    var historyBusy by mutableStateOf(false); private set
    var historyEnabled by mutableStateOf(false); private set
    var historyClearPending by mutableStateOf(false); private set

    private fun restoreHistory(account: String) {
        val saved = repository.listening.historySync()
        historyEnabled = saved?.account == account
        historyRelay = if (historyEnabled) saved?.relay.orEmpty() else ""
        if (historyEnabled) syncHistory()
    }

    fun setHistoryForeground(foreground: Boolean) {
        historyForeground = foreground
        if (foreground) syncHistory() else delayedHistorySync?.cancel()
    }

    fun configureHistory(relayText: String) {
        val connected = signer ?: return
        val relay = runCatching {
            val uri = URI(relayText.trim())
            require(uri.scheme == "wss" && uri.host != null && uri.rawUserInfo == null &&
                uri.rawQuery == null && uri.rawFragment == null && relayText.length <= 2048)
            URI("wss", null, uri.host.lowercase(), uri.port.takeUnless { it == 443 } ?: -1,
                uri.path.ifEmpty { "/" }, null, null).toASCIIString()
        }.getOrElse {
            historyStatus = "Enter a secure wss:// relay URL."
            return
        }
        repository.listening.configureHistorySync(connected.pubkey, relay)
        historyEnabled = true
        historyRelay = relay
        syncHistory()
    }

    fun disableHistory() {
        historyJob?.cancel()
        delayedHistorySync?.cancel()
        repository.listening.disableHistorySync()
        historyEnabled = false
        historyClearPending = false
        historyRelay = ""
        historyStatus = "History is local."
    }

    fun syncHistory() {
        val connected = signer ?: return
        val initial = repository.listening.historySync()?.takeIf { it.account == connected.pubkey } ?: return
        historyClearPending = initial.clearPending
        if (historyJob?.isActive == true) return
        historyBusy = true
        historyStatus = if (initial.clearPending) "Syncing pending history clear…" else "Syncing history…"
        historyJob = scope.launch {
            try {
                mutex.withLock {
                    currentCoroutineContext().ensureActive()
                    val address = "${NostrHistory.TOPIC}:${initial.device}"
                    val fromRelay = relays.history(initial.relay, connected)
                    val newest = fromRelay.asSequence().filter { event ->
                        event.optInt("kind") == NostrHistory.KIND && NostrHistory.encrypted(event.optString("content"))
                    }.mapNotNull { event ->
                        val tags = event.optJSONArray("tags") ?: return@mapNotNull null
                        val rows = (0 until tags.length()).mapNotNull(tags::optJSONArray)
                        if (rows.none { it.optString(0) == "t" && it.optString(1) == NostrHistory.TOPIC }) return@mapNotNull null
                        val addresses = rows.filter { it.optString(0) == "d" }
                        val device = addresses.singleOrNull()?.optString(1) ?: return@mapNotNull null
                        if (!NostrHistory.devicePattern.matches(device) ||
                            event.optLong("created_at") !in 1..(System.currentTimeMillis() / 1000 + 300)) return@mapNotNull null
                        device to event
                    }.groupBy({ it.first }, { it.second }).mapValues { (_, events) ->
                        events.sortedWith(compareByDescending<JSONObject> { it.optLong("created_at") }.thenBy { it.optString("id") }).first()
                    }
                    val remote = ArrayList<NostrHistory.Snapshot>()
                    for ((device, event) in newest) {
                        if (device == address) continue
                        val plaintext = connected.decryptHistory(event.getString("content"))
                        runCatching { NostrHistory.decode(plaintext) }.getOrNull()?.let(remote::add)
                    }
                    currentCoroutineContext().ensureActive()
                    check(signer === connected)
                    val work = repository.listening.acceptRemoteHistory(initial, remote) ?: return@withLock
                    if (work.dirty) {
                        val payload = NostrHistory.merge(listOf(work.local, NostrHistory.Snapshot(work.remote.clearBefore)))
                        val ciphertext = connected.encryptHistory(NostrHistory.encode(payload))
                        require(NostrHistory.encrypted(ciphertext)) { "Signer returned invalid encrypted history." }
                        val publishedAt = maxOf(System.currentTimeMillis() / 1000, work.lastPublished + 1,
                            (newest[address]?.optLong("created_at") ?: 0L) + 1)
                        val template = JSONObject().put("kind", NostrHistory.KIND).put("created_at", publishedAt)
                            .put("tags", JSONArray().put(JSONArray().put("d").put(address)).put(JSONArray().put("t").put(NostrHistory.TOPIC)))
                            .put("content", ciphertext)
                        val event = validateSignedEvent(template, connected.sign(template), connected.pubkey)
                        currentCoroutineContext().ensureActive()
                        check(signer === connected && repository.listening.historySync()?.relay == work.relay)
                        relays.publishHistory(work.relay, event, connected)
                        currentCoroutineContext().ensureActive()
                        repository.listening.acknowledgeHistory(work, publishedAt)
                    }
                    val remaining = repository.listening.historySync()
                    historyClearPending = remaining?.clearPending == true
                    historyStatus = when {
                        remaining?.clearPending == true -> "History clear is pending. Retry sync."
                        remaining?.dirty == true -> "New history is pending. Retry sync."
                        else -> "Listening history synced."
                    }
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                historyClearPending = repository.listening.historySync()?.clearPending == true
                historyStatus = if (historyClearPending)
                    "History cleared on this phone. Relay update is pending. Retry sync."
                else "Could not sync history. You can still listen and retry."
            } finally {
                historyBusy = false
            }
        }
    }

    fun restore(bridge: AndroidSignerBridge) {
        if (signer != null || busy) return
        val saved = runCatching { JSONObject(credentials.get("nostrSession")) }.getOrNull() ?: return
        runOperation {
            val restored = if (saved.getString("type") == "android")
                bridge.restore(saved.getString("pubkey"), saved.getString("package"))
            else withContext(Dispatchers.IO) { NostrRemoteSigner.restore(saved.getString("bunker"), saved.getString("secret"), saved.getString("pubkey"), authenticationCallback()) }
            signer = restored
            pubkey = restored.pubkey
            restoreHistory(restored.pubkey)
            status = "Nostr connected. Radio favorite changes are public."
        }
    }

    fun connectAndroid(bridge: AndroidSignerBridge) = runOperation {
        val connected = bridge.connect()
        replaceSigner(connected)
        credentials.set("nostrSession", JSONObject().put("type", "android").put("pubkey", connected.pubkey)
            .put("package", requireNotNull(bridge.connectedPackageName)).toString())
        syncNow(connected)
    }

    fun connectRemote(bunker: String) = runOperation {
        val secret = withContext(Dispatchers.IO) { NostrCrypto.generateSecret() }
        val connected = withContext(Dispatchers.IO) { withTimeout(90_000) { NostrRemoteSigner.connect(bunker, secret, authenticationCallback()) } }
        replaceSigner(connected)
        credentials.set("nostrSession", JSONObject().put("type", "remote").put("pubkey", connected.pubkey)
            .put("bunker", android.net.Uri.parse(bunker).buildUpon().clearQuery().apply {
                val parsed = android.net.Uri.parse(bunker)
                parsed.queryParameterNames.filter { it != "secret" }.forEach { name ->
                    parsed.getQueryParameters(name).forEach { appendQueryParameter(name, it) }
                }
            }.build().toString()).put("secret", secret).toString())
        syncNow(connected)
    }

    private fun replaceSigner(connected: NostrSigner) {
        (signer as? Closeable)?.close()
        signer = connected
        pubkey = connected.pubkey
        restoreHistory(connected.pubkey)
        status = "Nostr connected. Radio favorite changes are public."
    }

    private fun authenticationCallback(): (String) -> Unit {
        val epoch = generation
        return { url ->
            if (url.startsWith("https://") && url.length <= 8192) scope.launch {
                if (epoch == generation) authUrl = url
            }
        }
    }
    fun dismissAuthUrl() { authUrl = null }

    fun disconnect() {
        disableHistory()
        generation++
        operationToken++
        job?.cancel()
        (signer as? Closeable)?.close()
        signer = null; pubkey = ""; busy = false; authUrl = null
        credentials.set("nostrSession", "")
        status = "Nostr disconnected. Favorites stay on this phone."
    }

    fun close() {
        job?.cancel()
        historyJob?.cancel()
        delayedHistorySync?.cancel()
        stopHistoryObserver()
        (signer as? Closeable)?.close()
    }

    fun favoriteChanged(mediaId: String, starred: Boolean) {
        val connected = signer ?: return
        if (!mediaId.startsWith("radio:")) return
        val station = repository.stations.firstOrNull { it.id == mediaId } ?: return
        // Persist unsigned intent before asking the signer, so a denied/offline change remains retryable.
        val now = maxOf(System.currentTimeMillis() / 1000, lastCreatedAt + 1)
        lastCreatedAt = now
        val template = RadiostrFavorites.template(mediaId.removePrefix("radio:"), station.title, starred, now)
        val pending = pending(connected.pubkey).filterNot { stationId(it) == stationId(template) } + template
        savePending(connected.pubkey, pending.takeLast(500))
        if (!busy) sync()
        else status = "Favorite saved locally; sync to publish pending changes."
    }

    fun sync() = runOperation { signer?.let { syncNow(it) } ?: error("Connect a Nostr signer first.") }

    private suspend fun syncNow(connected: NostrSigner) {
        val epoch = generation
        // Flush first so an older relay action cannot overwrite a pending local choice.
        for (template in pending(connected.pubkey)) {
            val signed = validateSignedEvent(template, connected.sign(template), connected.pubkey)
            currentCoroutineContext().ensureActive()
            check(epoch == generation && signer === connected)
            relays.publish(signed, connected)
            currentCoroutineContext().ensureActive()
            check(epoch == generation && signer === connected)
            val receipts = readEvents("nostrReceipts:${connected.pubkey}")
                .filterNot { stationId(it) == stationId(signed) } + signed
            credentials.set("nostrReceipts:${connected.pubkey}", JSONArray(receipts.takeLast(500)).toString())
            savePending(connected.pubkey, pending(connected.pubkey).filterNot { it.toString() == template.toString() })
        }
        val events = relays.favorites(connected)
        currentCoroutineContext().ensureActive()
        check(epoch == generation && signer === connected)
        val waiting = pending(connected.pubkey).mapNotNull(::stationId).toSet()
        val known = repository.stations.map { it.id }.toSet()
        val merged = RadiostrFavorites.merge(repository.listening.favoriteIds(),
            (events + readEvents("nostrReceipts:${connected.pubkey}")).filter { stationId(it) !in waiting }, connected.pubkey, System.currentTimeMillis() / 1000)
            .filter { !it.startsWith("radio:") || it in known }
        repository.listening.replaceFavorites(merged)
        status = if (waiting.isEmpty()) "Nostr favorites synced. Radio favorite changes are public."
            else "Favorites saved locally; sync to publish pending changes."
    }

    private fun pending(key: String) = readEvents("nostrPending:$key")
    private fun readEvents(storageKey: String): List<JSONObject> = runCatching {
        val rows = JSONArray(credentials.get(storageKey).ifEmpty { "[]" })
        (0 until minOf(rows.length(), 500)).map { rows.getJSONObject(it) }
    }.getOrDefault(emptyList())
    private fun savePending(key: String, items: List<JSONObject>) {
        credentials.set("nostrPending:$key", JSONArray(items).toString())
    }
    private fun stationId(event: JSONObject): String? {
        val tags = event.optJSONArray("tags") ?: return null
        return (0 until tags.length()).mapNotNull { tags.optJSONArray(it) }
            .firstOrNull { it.optString(0) == "radiostr_station" }?.optString(1)
    }
    private fun runOperation(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        val token = ++operationToken
        job = scope.launch {
            try { mutex.withLock { block() } }
            catch (_: android.content.ActivityNotFoundException) { status = "Install an Android Nostr signer, such as Amber or Signstr, then connect again." }
            catch (_: TimeoutCancellationException) { status = "Nostr request timed out. Local favorites are kept; retry sync or reconnect." }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { status = "Nostr request failed or was declined. Local favorites are kept; retry sync or reconnect." }
            finally { if (token == operationToken) busy = false }
        }
    }
}
