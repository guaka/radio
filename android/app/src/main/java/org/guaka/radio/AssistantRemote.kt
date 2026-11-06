package org.guaka.radio

import android.content.Context
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import org.json.JSONObject
import org.webrtc.*
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Remote IDs pin the first 128 bits of the peer's SHA-256 DTLS certificate. */
internal object AssistantRemoteIdentity {
    fun normalize(raw: String): String {
        val id = raw.replace("-", "").filterNot(Char::isWhitespace).uppercase(Locale.ROOT)
        require(id.matches(Regex("[A-Z2-7]{25}[AEIMQUY4]"))) { "Enter the Music Assistant Remote ID from Settings → Remote Access." }
        return id
    }
    fun verify(id: String, sdp: String) {
        val fingerprints = sdp.lineSequence().map(String::trim).filter { it.startsWith("a=fingerprint:") }.toList()
        require(fingerprints.isNotEmpty()) { "Remote server identity could not be verified." }
        fingerprints.forEach { line ->
            val hex = line.removePrefix("a=fingerprint:sha-256 ").replace(":", "")
            require(line.startsWith("a=fingerprint:sha-256 ") && hex.matches(Regex("[0-9A-Fa-f]{64}"))) { "Remote server identity could not be verified." }
            val bytes = hex.chunked(2).take(16).map { it.toInt(16) }
            val bits = bytes.joinToString("") { it.toString(2).padStart(8, '0') } + "00"
            val encoded = bits.chunked(5).joinToString("") { "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"[it.toInt(2)].toString() }
            require(encoded == normalize(id)) { "Remote server identity does not match the Remote ID." }
        }
    }
}

/** One bounded authenticated command per connection; no credentials are sent to signaling. */
internal class AssistantRemote(context: Context) {
    private val factory: PeerConnectionFactory by lazy {
        synchronized(AssistantRemote::class.java) {
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions())
            PeerConnectionFactory.builder().createPeerConnectionFactory()
        }
    }
    private val signalingHttp = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
    suspend fun command(remoteId: String, token: String, command: String, args: JSONObject = JSONObject()): Any = try { withTimeout(35_000) {
        val id = AssistantRemoteIdentity.normalize(remoteId)
        val events = Channel<JSONObject>(128)
        var peer: PeerConnection? = null
        var data: DataChannel? = null
        var socket: WebSocket? = null
        var session = ""
        fun fail() { events.close(IOException("Remote connection failed. Check Music Assistant Remote Access and your network.")) }
        fun emit(event: JSONObject) { if (!events.trySend(event).isSuccess) fail() }
        fun signal(type: String, payload: JSONObject) {
            if (socket?.send(JSONObject().put("type", type).put("remoteId", id).put("sessionId", session).put("data", payload).toString()) != true) fail()
        }
        try {
            socket = signalingHttp.newWebSocket(Request.Builder().url("wss://signaling.music-assistant.io/ws").build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(JSONObject().put("type", "connect-request").put("remoteId", id).toString())
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.length > 1024 * 1024) { fail(); return }
                    try { emit(JSONObject(text)) } catch (_: Exception) { fail() }
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { fail() }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { fail() }
            })
            var ready = false
            var authSent = false
            var authenticated = false
            val chunks = AssistantRemoteChunks()
            val pendingIce = mutableListOf<IceCandidate>()
            while (true) {
                val message = events.receive()
                when (message.optString("type")) {
                    "ping" -> socket.send("{\"type\":\"pong\"}")
                    "error", "peer-disconnected" -> throw IOException("Music Assistant Remote Access is unavailable. Check the Remote ID and server status.")
                    "connected" -> {
                        check(peer == null) { "Unexpected remote session." }
                        session = message.getString("sessionId")
                        val servers = message.optJSONArray("iceServers") ?: org.json.JSONArray()
                        val ice = (0 until servers.length()).map { i ->
                            val server = servers.getJSONObject(i)
                            val urls = server.optJSONArray("urls")?.let { a -> (0 until a.length()).map { a.getString(it) } }
                                ?: listOf(server.getString("urls"))
                            PeerConnection.IceServer.builder(urls).setUsername(server.optString("username")).setPassword(server.optString("credential")).createIceServer()
                        }
                        peer = factory.createPeerConnection(PeerConnection.RTCConfiguration(ice), object : PeerConnection.Observer {
                            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
                            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) { if (state == PeerConnection.IceConnectionState.FAILED) fail() }
                            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
                            override fun onIceCandidate(candidate: IceCandidate) { signal("ice-candidate", JSONObject().put("candidate", candidate.sdp).put("sdpMid", candidate.sdpMid).put("sdpMLineIndex", candidate.sdpMLineIndex)) }
                            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
                            override fun onAddStream(stream: MediaStream) {}
                            override fun onRemoveStream(stream: MediaStream) {}
                            override fun onDataChannel(channel: DataChannel) {}
                            override fun onRenegotiationNeeded() {}
                        }) ?: throw IOException("Cannot initialize remote connection.")
                        data = peer.createDataChannel("ma-api", DataChannel.Init())
                        data.registerObserver(object : DataChannel.Observer {
                            override fun onBufferedAmountChange(amount: Long) {}
                            override fun onStateChange() { if (data?.state() == DataChannel.State.CLOSED) fail() }
                            override fun onMessage(buffer: DataChannel.Buffer) {
                                if (buffer.binary || buffer.data.remaining() > 1024 * 1024) { fail(); return }
                                val bytes = ByteArray(buffer.data.remaining()); buffer.data.get(bytes)
                                try { emit(JSONObject().put("type", "api").put("body", JSONObject(String(bytes, Charsets.UTF_8)))) } catch (_: Exception) { fail() }
                            }
                        })
                        val offer = sdp { peer.createOffer(it, MediaConstraints()) }
                        sdp { peer.setLocalDescription(it, offer) }
                        signal("offer", JSONObject().put("type", "offer").put("sdp", offer.description))
                    }
                    "answer" -> {
                        check(!ready && message.getString("sessionId") == session) { "Unexpected remote answer." }
                        val answer = message.getJSONObject("data").getString("sdp")
                        AssistantRemoteIdentity.verify(id, answer)
                        sdp { peer!!.setRemoteDescription(it, SessionDescription(SessionDescription.Type.ANSWER, answer)) }
                        ready = true
                        pendingIce.forEach { peer!!.addIceCandidate(it) }; pendingIce.clear()
                    }
                    "ice-candidate" -> if (message.optString("sessionId") == session) {
                        val c = message.getJSONObject("data")
                        val candidate = IceCandidate(c.optString("sdpMid", "0"), c.optInt("sdpMLineIndex", 0), c.getString("candidate"))
                        if (ready) peer!!.addIceCandidate(candidate) else {
                            check(pendingIce.size < 256) { "Too many remote candidates." }; pendingIce.add(candidate)
                        }
                    }
                    "api" -> {
                        check(ready) { "Remote server identity has not been verified." }
                        val body = chunks.accept(message.getJSONObject("body")) ?: continue
                        fun send(name: String, requestId: String, arguments: JSONObject) {
                            val payload = JSONObject().put("command", name).put("message_id", requestId).put("args", arguments).toString()
                            check(data!!.send(DataChannel.Buffer(ByteBuffer.wrap(payload.toByteArray(Charsets.UTF_8)), false))) { "Remote request could not be sent." }
                        }
                        if (!authSent && body.optString("server_id").isNotBlank()) {
                            authSent = true
                            send("auth", "auth", JSONObject().put("token", token).put("device_name", "Radio Guaka"))
                        } else if (body.optString("message_id") == "auth") {
                            if (body.has("error_code") || body.has("error")) throw IOException("Music Assistant access denied. Check the access token.")
                            authenticated = true
                            send(command, "command", args)
                        } else if (authenticated && body.optString("message_id") == "command") {
                            if (body.has("error_code") || body.has("error")) throw AssistantRemoteCommandException(body.optInt("error_code", -1), body.optString("details").take(1024))
                            return@withTimeout body.opt("result") ?: JSONObject.NULL
                        }
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE") JSONObject.NULL
        } finally {
            socket?.cancel()
            data?.unregisterObserver(); data?.close(); data?.dispose()
            peer?.close(); peer?.dispose()
            events.cancel()
        }
    }
    } catch (_: TimeoutCancellationException) {
        throw IOException("Music Assistant remote connection timed out. Check Remote Access and your network.")
    }
    private suspend fun sdp(start: (SdpObserver) -> Unit): SessionDescription = suspendCancellableCoroutine { continuation ->
        start(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) { if (continuation.isActive) continuation.resume(description) }
            override fun onSetSuccess() { if (continuation.isActive) continuation.resume(SessionDescription(SessionDescription.Type.OFFER, "")) }
            override fun onCreateFailure(error: String) { if (continuation.isActive) continuation.resumeWithException(IOException("Remote connection negotiation failed.")) }
            override fun onSetFailure(error: String) = onCreateFailure(error)
        })
    }
}

/** Bound reassembly of the gateway's large-response envelopes. Confined to the command coroutine. */
internal class AssistantRemoteChunks {
    private data class Group(val parts: Array<ByteArray?>, var bytes: Int = 0)
    private val groups = mutableMapOf<Int, Group>()
    fun accept(frame: JSONObject): JSONObject? {
        if (frame.optString("type") != "__chunk__") return frame
        val count = frame.getInt("count")
        val seq = frame.getInt("seq")
        check(count in 1..128 && seq in 0 until count) { "Invalid remote response chunks." }
        val id = frame.getInt("id")
        val group = groups[id] ?: run {
            check(groups.size < 4) { "Too many remote response chunks." }
            Group(arrayOfNulls(count)).also { groups[id] = it }
        }
        check(group.parts.size == count) { "Inconsistent remote response chunks." }
        val bytes = java.util.Base64.getDecoder().decode(frame.getString("b64"))
        group.bytes += bytes.size - (group.parts[seq]?.size ?: 0)
        check(group.bytes <= 2 * 1024 * 1024) { "Music Assistant response is too large." }
        group.parts[seq] = bytes
        if (group.parts.any { it == null }) return null
        groups.remove(id)
        val output = java.io.ByteArrayOutputStream(group.bytes)
        group.parts.forEach { output.write(it!!) }
        return JSONObject(output.toString("UTF-8"))
    }
}

internal class AssistantRemoteCommandException(val errorCode: Int, val details: String = "") : IOException(
    "Music Assistant rejected the command (error $errorCode). Check the source, player, and token permissions."
)
