package org.guaka.radio

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal enum class SpeakerSourceMode { PHONE_RELAY, MUSIC_ASSISTANT }

internal data class SpeakerMedia(val uri: String, val relay: PhoneAudioRelaySession? = null)

/** Only audio bytes cross this endpoint; origin URLs and Plex credentials stay on the phone. */
internal class PhoneAudioRelaySession(
    val entry: Entry,
    address: InetAddress,
    http: OkHttpClient,
    private val stillValid: () -> Boolean = { true },
) : Closeable {
    private val secret = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private val path = "/audio/$secret"
    private val sourceClient = PlaybackHttpClient.forEntry(http, entry).also {
        require(entry.url.startsWith("https://")) { "Phone relay requires a secure upstream audio source." }
    }
    private val server = ServerSocket(0, 8, address)
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val calls = ConcurrentHashMap.newKeySet<okhttp3.Call>()
    private val received = CompletableDeferred<Unit>()
    val acceptedRequests = java.util.concurrent.atomic.AtomicInteger()
    val deliveredBytes = java.util.concurrent.atomic.AtomicLong()
    private val expiresAt = System.nanoTime() + TimeUnit.HOURS.toNanos(6)
    private val workers = ThreadPoolExecutor(0, 8, 30L, TimeUnit.SECONDS, SynchronousQueue(), { task ->
        Thread(task, "radio-audio-relay-request").apply { isDaemon = true }
    })
    @Volatile private var closed = false
    val uri: String = "http://${address.hostAddress}:${server.localPort}$path"
    val isOpen get() = !closed && System.nanoTime() <= expiresAt

    init {
        Thread({
            while (!closed) {
                try {
                    val socket = server.accept()
                    socket.soTimeout = 10_000
                    clients.add(socket)
                    try { workers.execute { serve(socket) } }
                    catch (_: java.util.concurrent.RejectedExecutionException) { clients.remove(socket); socket.close() }
                } catch (_: IOException) { if (!closed) close() }
            }
        }, "radio-audio-relay-listener").apply { isDaemon = true; start() }
    }

    suspend fun awaitReceiver() {
        check(withTimeoutOrNull(15_000) { received.await(); true } == true) {
            "Music Assistant could not fetch audio from this phone. Keep Nebula connected or use the same reachable LAN."
        }
    }

    private fun serve(socket: Socket) {
        var call: okhttp3.Call? = null
        try {
            socket.use {
                val input = BufferedInputStream(socket.getInputStream())
                fun line(): String {
                    val bytes = java.io.ByteArrayOutputStream()
                    while (bytes.size() < 4096) {
                        val next = input.read()
                        if (next < 0) throw IOException("Incomplete relay request.")
                        if (next == 10) return bytes.toString("US-ASCII").removeSuffix("\r")
                        bytes.write(next)
                    }
                    throw IOException("Relay header is too long.")
                }
                val first = line().split(' ')
                if (first.size != 3) { reply(socket, 400); return }
                val method = first[0]
                var range: String? = null
                var invalidRange = false
                var headerBytes = 0
                while (true) {
                    val header = line()
                    if (header.isEmpty()) break
                    headerBytes += header.length
                    if (headerBytes > 8192) { reply(socket, 431); return }
                    if (header.substringBefore(':').equals("Range", true)) {
                        val value = header.substringAfter(':').trim()
                        if (range != null || !validRelayRange(value)) invalidRange = true
                        range = value
                    }
                }
                // Drain bounded request headers before a response so closing cannot reset
                // a well-formed rejected request with unread header bytes still pending.
                if (first[1] != path || closed || System.nanoTime() > expiresAt || !stillValid()) { reply(socket, 404); return }
                if (method !in setOf("GET", "HEAD")) { reply(socket, 405); return }
                if (invalidRange) { reply(socket, 416); return }
                acceptedRequests.incrementAndGet()
                val request = Request.Builder().url(entry.url).header("Accept-Encoding", "identity")
                range?.let { request.header("Range", it) }
                if (method == "HEAD") request.head()
                call = sourceClient.newCall(request.build()).also(calls::add)
                call!!.execute().use { response ->
                    if (closed || !stillValid()) { reply(socket, 404); return }
                    if (response.code !in setOf(200, 206, 416)) { reply(socket, 502); return }
                    val mime = response.header("Content-Type").orEmpty().substringBefore(';').lowercase()
                    if (response.code != 416 && (mime.contains("mpegurl") || mime.contains("dash") || mime.startsWith("text/"))) {
                        reply(socket, 502); return
                    }
                    val output = socket.getOutputStream()
                    val headers = buildString {
                        append("HTTP/1.1 ${response.code} ${if (response.code == 206) "Partial Content" else if (response.code == 416) "Range Not Satisfiable" else "OK"}\r\n")
                        if (response.code == 416) append("Content-Length: 0\r\n")
                        for (name in listOf("Content-Type", "Content-Length", "Content-Range", "Accept-Ranges")) {
                            if (response.code == 416 && name == "Content-Length") continue
                            response.header(name)?.takeIf { '\r' !in it && '\n' !in it }?.let { append("$name: $it\r\n") }
                        }
                        append("Cache-Control: no-store\r\nConnection: close\r\n\r\n")
                    }
                    output.write(headers.toByteArray(Charsets.US_ASCII))
                    if (method == "GET" && response.code != 416) {
                        val source = response.body?.byteStream() ?: throw IOException("Empty audio response.")
                        val buffer = ByteArray(32 * 1024)
                        while (!closed && stillValid()) {
                            val count = source.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            output.flush()
                            deliveredBytes.addAndGet(count.toLong())
                            received.complete(Unit)
                        }
                    }
                    output.flush()
                }
            }
        } catch (_: Exception) { /* Never log source URLs, session capabilities or credential headers. */ }
        finally { call?.let { calls.remove(it); it.cancel() }; clients.remove(socket); runCatching { socket.close() } }
    }

    override fun close() {
        closed = true
        runCatching { server.close() }
        clients.forEach { runCatching { it.close() } }
        calls.forEach { it.cancel() }
        workers.shutdownNow()
        received.cancel()
    }

    private fun reply(socket: Socket, code: Int) {
        socket.getOutputStream().write("HTTP/1.1 $code Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
    }
}

internal fun validRelayRange(value: String): Boolean =
    value.matches(Regex("bytes=(?:[0-9]{1,18}-[0-9]{0,18}|-[0-9]{1,18})"))

internal fun phoneRelayAddresses(): List<Inet4Address> {
    val interfaces = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
    fun addresses(network: NetworkInterface) = network.inetAddresses.toList().filterIsInstance<Inet4Address>()
        .filter { !it.isLoopbackAddress && it.isSiteLocalAddress }
    val ordered = interfaces.filter { it.name.startsWith("tun") } +
        interfaces.filter { it.name.startsWith("wlan") || it.name.startsWith("eth") }
    return ordered.flatMap(::addresses).distinctBy { it.hostAddress }.also {
        check(it.isNotEmpty()) { "Connect Nebula or Wi-Fi so Music Assistant can reach this phone’s audio relay." }
    }
}

internal fun phoneRelayAddress(): Inet4Address = phoneRelayAddresses().first()
