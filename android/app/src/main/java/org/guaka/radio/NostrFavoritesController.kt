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

    fun restore(bridge: AndroidSignerBridge) {
        if (signer != null || busy) return
        val saved = runCatching { JSONObject(credentials.get("nostrSession")) }.getOrNull() ?: return
        runOperation {
            val restored = if (saved.getString("type") == "android")
                bridge.restore(saved.getString("pubkey"), saved.getString("package"))
            else withContext(Dispatchers.IO) { NostrRemoteSigner.restore(saved.getString("bunker"), saved.getString("secret"), saved.getString("pubkey"), authenticationCallback()) }
            signer = restored
            pubkey = restored.pubkey
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
        generation++
        operationToken++
        job?.cancel()
        (signer as? Closeable)?.close()
        signer = null; pubkey = ""; busy = false; authUrl = null
        credentials.set("nostrSession", "")
        status = "Nostr disconnected. Favorites stay on this phone."
    }

    fun close() { job?.cancel(); (signer as? Closeable)?.close() }

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
