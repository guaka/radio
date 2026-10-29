package org.guaka.radio

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.IOpenNostrRequest
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.req
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.sockets.okhttp.BasicOkHttpWebSocket
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip46RemoteSigner.BunkerRequest
import com.vitorpamplona.quartz.nip46RemoteSigner.BunkerRequestConnect
import com.vitorpamplona.quartz.nip46RemoteSigner.BunkerRequestGetPublicKey
import com.vitorpamplona.quartz.nip46RemoteSigner.BunkerRequestSign
import com.vitorpamplona.quartz.nip46RemoteSigner.BunkerResponse
import com.vitorpamplona.quartz.nip46RemoteSigner.NostrConnectEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** NIP-46 RPC over Quartz NIP-01/NIP-44 primitives and its relay transport. */
internal class NostrRemoteSigner private constructor(
    private val session: Session,
    override val pubkey: String,
) : NostrSigner, Closeable {
    override suspend fun sign(template: JSONObject): JSONObject {
        val tagsJson = template.optJSONArray("tags") ?: JSONArray()
        val tags = Array(tagsJson.length()) { i ->
            val row = tagsJson.getJSONArray(i)
            Array(row.length()) { j -> row.getString(j) }
        }
        val eventTemplate = EventTemplate<Event>(
            createdAt = template.getLong("created_at"),
            kind = template.getInt("kind"),
            tags = tags,
            content = template.optString("content", ""),
        )
        val response = session.request(BunkerRequestSign(event = eventTemplate))
        val signedEvent = runCatching { JSONObject(requireNotNull(response.result)) }
            .getOrElse { throw IOException("Remote signer returned an invalid event.", it) }
        return validateSignedEvent(template, signedEvent, pubkey)
    }

    override fun close() = session.close()

    private class Session(
        val client: NostrClient,
        private val clientSigner: NostrSignerInternal,
        private val remotePubkey: String,
        private val relays: Set<NormalizedRelayUrl>,
        private val httpClient: OkHttpClient,
        private val scope: CoroutineScope,
        private val onAuthUrl: (String) -> Unit,
    ) {
        private val awaiting = ConcurrentHashMap<String, CompletableDeferred<BunkerResponse>>()
        private val seenAuthEvents = ConcurrentHashMap.newKeySet<String>()
        private var responseSubscription: IOpenNostrRequest? = null
        @Volatile private var closed = false

        fun startSubscription() {
            check(responseSubscription == null)
            val filter = Filter(
                kinds = listOf(NostrConnectEvent.KIND),
                tags = mapOf("p" to listOf(clientSigner.pubKey)),
            )
            responseSubscription = client.req(relays.toList(), filter) { event ->
                (event as? NostrConnectEvent)?.let(::handleResponse)
            }
        }

        suspend fun request(message: BunkerRequest): BunkerResponse {
            check(!closed) { "Remote signer is closed." }
            val deferred = CompletableDeferred<BunkerResponse>()
            check(awaiting.putIfAbsent(message.id, deferred) == null) { "Duplicate remote signer request ID." }
            try {
                check(!closed) { "Remote signer is closed." }
                val event = NostrConnectEvent.create(message, remotePubkey, clientSigner)
                client.send(event, relays)
                val response = withTimeout(RPC_TIMEOUT_MS) { deferred.await() }
                if (response.error != null) throw IOException("Remote signer rejected the request.")
                return response
            } catch (error: CancellationException) {
                throw error
            } finally {
                awaiting.remove(message.id, deferred)
            }
        }

        private fun handleResponse(event: NostrConnectEvent) {
            if (closed || !event.pubKey.equals(remotePubkey, ignoreCase = true)) return
            if (event.verifiedRecipientPubKey()?.equals(clientSigner.pubKey, ignoreCase = true) != true) return
            if (event.content.length > MAX_RESPONSE_CHARS) return
            if (!runCatching { NostrCrypto.verify(JSONObject(event.toJson())) }.getOrDefault(false)) return
            scope.launch {
                // Quartz 1.05 maps every response with an `error` field to BunkerResponseError,
                // dropping `result`; NIP-46 auth_url specifically carries both. Decode the JSON
                // after Quartz's NIP-44 decrypt so we can preserve that challenge shape.
                val response = runCatching {
                    val plaintext = clientSigner.nip44Decrypt(event.content, remotePubkey)
                    parseBunkerResponse(plaintext)
                }.getOrNull() ?: return@launch
                val deferred = awaiting[response.id] ?: return@launch
                if (response.result == AUTH_URL_RESULT) {
                    val url = response.error
                    if (url != null && isSafeAuthUrl(url)) {
                        if (seenAuthEvents.size > 128) seenAuthEvents.clear()
                        if (seenAuthEvents.add(event.id)) runCatching { onAuthUrl(url) }
                        // The signer resumes the same request ID after the user authenticates.
                        return@launch
                    }
                    deferred.completeExceptionally(IOException("Remote signer returned an invalid authentication URL."))
                    return@launch
                }
                deferred.complete(response)
            }
        }

        fun close() {
            if (closed) return
            closed = true
            awaiting.values.forEach { it.cancel() }
            awaiting.clear()
            try {
                responseSubscription?.close()
                client.disconnect()
            } finally {
                scope.cancel()
                httpClient.dispatcher.cancelAll()
                httpClient.dispatcher.executorService.shutdown()
                httpClient.connectionPool.evictAll()
            }
        }
    }

    companion object {
        private const val MAX_RELAYS = 8
        private const val RPC_TIMEOUT_MS = 80_000L
        private const val MAX_RESPONSE_CHARS = 64 * 1024
        private const val AUTH_URL_RESULT = "auth_url"
        private const val CONNECT_ACK = "ack"

        suspend fun connect(
            bunkerUrl: String,
            clientSecret: String,
            onAuthUrl: (String) -> Unit = {},
        ): NostrRemoteSigner {
            val bunker = parseBunker(bunkerUrl)
            val session = createSession(bunker, clientSecret, onAuthUrl)
            try {
                session.startSubscription()
                session.client.connect()
                val connectResult = session.request(
                    BunkerRequestConnect(remoteKey = bunker.remotePubkey, secret = bunker.secret),
                ).result
                val userPubkey = session.request(BunkerRequestGetPublicKey()).result
                    ?.lowercase()
                    ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
                    ?: throw IOException("Remote signer returned an invalid public key.")
                val connectAccepted = connectResult == CONNECT_ACK ||
                    (bunker.secret != null && connectResult == bunker.secret) ||
                    (connectResult?.matches(Regex("[0-9a-fA-F]{64}")) == true &&
                        connectResult.equals(userPubkey, ignoreCase = true))
                if (!connectAccepted) throw IOException("Remote signer returned an invalid connect confirmation.")
                return NostrRemoteSigner(session, userPubkey)
            } catch (error: CancellationException) {
                session.close()
                throw error
            } catch (error: Exception) {
                session.close()
                throw IllegalStateException("Could not connect to the remote signer.", error)
            }
        }

        suspend fun restore(
            bunkerUrl: String,
            clientSecret: String,
            userPubkey: String,
            onAuthUrl: (String) -> Unit = {},
        ): NostrRemoteSigner {
            require(userPubkey.matches(Regex("[0-9a-fA-F]{64}"))) { "Invalid saved user public key." }
            val bunker = parseBunker(bunkerUrl).copy(secret = null)
            val session = createSession(bunker, clientSecret, onAuthUrl)
            try {
                session.startSubscription()
                session.client.connect()
                val actualPubkey = session.request(BunkerRequestGetPublicKey()).result?.lowercase()
                require(actualPubkey == userPubkey.lowercase()) { "The remote signer account changed." }
                return NostrRemoteSigner(session, requireNotNull(actualPubkey))
            } catch (error: CancellationException) {
                session.close()
                throw error
            } catch (error: Exception) {
                session.close()
                throw IllegalStateException("Could not restore the remote signer.", error)
            }
        }

        private data class ParsedBunker(
            val remotePubkey: String,
            val relays: Set<NormalizedRelayUrl>,
            val secret: String?,
        )

        private fun parseBunker(url: String): ParsedBunker {
            require(url.length <= 8192) { "Bunker URL is too long." }
            val uri = runCatching { URI(url) }.getOrElse { throw IllegalArgumentException("Invalid bunker URL.", it) }
            require(uri.scheme.equals("bunker", ignoreCase = true) && uri.userInfo == null && uri.fragment == null) {
                "Invalid bunker URL."
            }
            val remotePubkey = uri.host?.lowercase()?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
                ?: throw IllegalArgumentException("Invalid bunker public key.")
            val queryValues = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.map { pair ->
                val split = pair.split('=', limit = 2)
                decodeQueryComponent(split[0]) to decodeQueryComponent(split.getOrElse(1) { "" })
            }
            val relayValues = queryValues.filter { it.first == "relay" }.map { it.second }.distinct()
            require(relayValues.isNotEmpty() && relayValues.size <= MAX_RELAYS) {
                "Bunker URL must contain between one and $MAX_RELAYS relays."
            }
            val relays = relayValues.map { relay ->
                require(relay.length <= 2048 && relay.startsWith("wss://", ignoreCase = true)) {
                    "Bunker relays must use secure WebSockets."
                }
                val relayUri = runCatching { URI(relay) }
                    .getOrElse { throw IllegalArgumentException("Invalid bunker relay URL.", it) }
                require(relayUri.scheme.equals("wss", ignoreCase = true) && !relayUri.host.isNullOrBlank() &&
                    relayUri.userInfo == null && relayUri.fragment == null && relayUri.port <= 65535) {
                    "Invalid bunker relay URL."
                }
                NormalizedRelayUrl(relay)
            }.toSet()
            val secret = queryValues.firstOrNull { it.first == "secret" }?.second?.takeIf { it.isNotBlank() }
            require(secret == null || secret.length <= 1024) { "Bunker secret is too long." }
            return ParsedBunker(remotePubkey, relays, secret)
        }

        private fun createSession(bunker: ParsedBunker, clientSecret: String, onAuthUrl: (String) -> Unit): Session {
            require(clientSecret.matches(Regex("[0-9a-fA-F]{64}"))) { "Client transport secret must be 32-byte hex." }
            val keyPair = KeyPair(clientSecret.hexToByteArray())
            require(keyPair.pubKey.size == 32) { "Invalid client transport secret." }
            val httpClient = OkHttpClient.Builder().pingInterval(30, TimeUnit.SECONDS).connectTimeout(15, TimeUnit.SECONDS).build()
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val client = NostrClient(BasicOkHttpWebSocket.Builder { httpClient }, scope)
            return Session(client, NostrSignerInternal(keyPair), bunker.remotePubkey, bunker.relays, httpClient, scope, onAuthUrl)
        }

        private fun isSafeAuthUrl(value: String): Boolean {
            if (value.length > 8192) return false
            val uri = runCatching { URI(value) }.getOrNull() ?: return false
            return uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() &&
                uri.userInfo == null && uri.port <= 65535
        }

        private fun decodeQueryComponent(value: String): String =
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())

    }
}

internal fun parseBunkerResponse(plaintext: String): BunkerResponse {
    require(plaintext.length <= 64 * 1024) { "Remote signer response is too large." }
    val json = JSONObject(plaintext)
    fun stringField(name: String): String? = if (!json.has(name) || json.isNull(name)) null else json.getString(name)
    return BunkerResponse(
        id = json.getString("id"),
        result = stringField("result"),
        error = stringField("error"),
    )
}
