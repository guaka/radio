package org.guaka.radio

import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Isolated public Nostr transport: no Plex or Music Assistant HTTP client/headers. */
internal class NostrRelays(
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS).build(),
    private val relays: List<String> = listOf("wss://relay.trustroots.org", "wss://relay.nomadwiki.org"),
) {

    suspend fun favorites(signer: NostrSigner): List<JSONObject> = supervisorScope {
        val results = relays.map { relay -> async {
            runCatching { exchange(relay, signer, null) }.also { currentCoroutineContext().ensureActive() }
        } }.awaitAll()
        check(results.any { it.isSuccess }) { "Could not read Nostr favorites. Try syncing again." }
        results.mapNotNull { it.getOrNull() }.flatten().distinctBy { it.getString("id") }
    }

    suspend fun publish(event: JSONObject, signer: NostrSigner) = supervisorScope {
        val results = relays.map { relay -> async {
            runCatching { exchange(relay, signer, event) }.also { currentCoroutineContext().ensureActive() }
        } }.awaitAll()
        check(results.any { it.isSuccess }) { "Favorites remain on this phone; relay publishing failed. Retry sync." }
    }

    private suspend fun exchange(relay: String, signer: NostrSigner, event: JSONObject?): List<JSONObject> =
        withTimeout(90_000) { coroutineScope {
            val scope = this
            suspendCancellableCoroutine { continuation ->
                val subscription = UUID.randomUUID().toString()
                val completed = AtomicBoolean(false)
                val events = mutableListOf<JSONObject>()
                val authId = java.util.concurrent.atomic.AtomicReference<String?>(null)
                var challenged = false
                val request = if (event != null) JSONArray().put("EVENT").put(event) else JSONArray()
                    .put("REQ").put(subscription).put(JSONObject().put("kinds", JSONArray().put(1))
                        .put("authors", JSONArray().put(signer.pubkey))
                        .put("#t", JSONArray().put("radiostr").put("favorite"))
                        .put("since", System.currentTimeMillis() / 1000 - 30L * 86400).put("limit", 500))
                val socket = client.newWebSocket(Request.Builder().url(relay).build(), object : WebSocketListener() {
                    fun finish(ws: WebSocket, error: String? = null) {
                        if (!completed.compareAndSet(false, true)) return
                        if (event == null) ws.send(JSONArray().put("CLOSE").put(subscription).toString())
                        ws.close(1000, null)
                        if (error == null) continuation.resume(events.toList())
                        else continuation.resumeWithException(IllegalStateException(error))
                    }
                    override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(request.toString()) }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (completed.get() || text.length > 128 * 1024) return
                        runCatching {
                            val message = JSONArray(text)
                            when (message.optString(0)) {
                                "EVENT" -> if (event == null && message.optString(1) == subscription && events.size < 500) {
                                    val received = message.getJSONObject(2)
                                    if (received.optString("pubkey") == signer.pubkey && NostrCrypto.verify(received)) events.add(received)
                                }
                                "EOSE" -> if (event == null && message.optString(1) == subscription) finish(webSocket)
                                "AUTH" -> if (!challenged) {
                                    challenged = true
                                    val challenge = message.getString(1)
                                    if (challenge.length > 4096) { finish(webSocket, "Invalid relay authentication challenge."); return }
                                    scope.launch {
                                        try {
                                            val template = JSONObject().put("kind", 22242).put("created_at", System.currentTimeMillis() / 1000)
                                                .put("content", "").put("tags", JSONArray().put(JSONArray().put("relay").put(relay))
                                                    .put(JSONArray().put("challenge").put(challenge)))
                                            val signed = validateSignedEvent(template, signer.sign(template), signer.pubkey)
                                            authId.set(signed.getString("id"))
                                            webSocket.send(JSONArray().put("AUTH").put(signed).toString())
                                        } catch (e: CancellationException) { throw e }
                                        catch (_: Exception) { finish(webSocket, "Relay authentication was not approved.") }
                                    }
                                }
                                "OK" -> {
                                    val id = message.optString(1)
                                    if (id == authId.get()) {
                                        if (message.optBoolean(2)) webSocket.send(request.toString())
                                        else finish(webSocket, "Relay authentication failed.")
                                    } else if (event != null && id == event.optString("id")) {
                                        if (message.optBoolean(2)) finish(webSocket)
                                        else if (!message.optString(3).startsWith("auth-required:")) finish(webSocket, "Relay rejected the favorite update.")
                                    }
                                }
                                "CLOSED" -> if (message.optString(1) == subscription && !message.optString(2).startsWith("auth-required:"))
                                    finish(webSocket, "Relay could not read favorites.")
                            }
                        }
                    }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = finish(webSocket, "Nostr relay connection failed.")
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = finish(webSocket, "Nostr relay connection closed.")
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = finish(webSocket, "Nostr relay connection closed.")
                })
                continuation.invokeOnCancellation { completed.set(true); socket.cancel() }
            }
        } }
}
