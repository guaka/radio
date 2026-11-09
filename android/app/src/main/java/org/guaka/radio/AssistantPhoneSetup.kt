package org.guaka.radio

import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal data class AssistantPhoneDetails(val target: String, val token: String)

/** Short-lived, single-submission LAN form. Credentials never appear in the URL or response. */
internal class AssistantPhoneSetup(
    address: InetAddress,
    private val local: Boolean,
    private val target: String,
    private val onReceived: (AssistantPhoneDetails) -> Unit,
    private val lifetimeMs: Long = TimeUnit.MINUTES.toMillis(5),
) : Closeable {
    private val secret = ByteArray(24).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private val path = "/setup/$secret"
    private val server = ServerSocket(0, 4, address)
    private val expires = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(lifetimeMs)
    private val used = AtomicBoolean(false)
    @Volatile private var closed = false
    @Volatile private var client: Socket? = null
    val url = "http://${address.hostAddress}:${server.localPort}$path"
    private val origin = "http://${address.hostAddress}:${server.localPort}"
    private val listener = Thread({
        server.soTimeout = 1000
        while (!closed && System.nanoTime() < expires) {
            try {
                val socket = server.accept()
                client = socket
                if (closed) socket.close() else socket.use { serve(it) }
            } catch (_: Exception) { /* Do not log submitted credentials or pairing URLs. */ }
            finally { client = null }
        }
        close()
    }, "assistant-phone-setup").apply { isDaemon = true; start() }

    private fun serve(socket: Socket) {
        socket.soTimeout = 3000
        val input = socket.getInputStream().buffered()
        fun line(): String {
            val bytes = java.io.ByteArrayOutputStream()
            while (bytes.size() < 4096) {
                val next = input.read()
                require(next >= 0)
                if (next == 10) return bytes.toString("US-ASCII").removeSuffix("\r")
                bytes.write(next)
            }
            error("Header too long")
        }
        val request = line().split(' ')
        val headers = mutableMapOf<String, String>()
        var count = 0
        while (true) {
            val header = line()
            if (header.isEmpty()) break
            require(++count <= 32)
            val parts = header.split(':', limit = 2)
            require(parts.size == 2)
            val name = parts[0].lowercase()
            require(name !in headers)
            headers[name] = parts[1].trim()
        }
        fun reply(code: Int, body: String) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            socket.getOutputStream().apply {
                write(("HTTP/1.1 $code Response\r\nContent-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\n" +
                    "Content-Security-Policy: default-src 'none'; form-action 'self'; frame-ancestors 'none'\r\n" +
                    "X-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n").toByteArray())
                write(bytes)
                flush()
            }
        }
        if (request.size != 3 || request[1] != path || headers["host"] != origin.removePrefix("http://")) {
            reply(404, "Not found"); return
        }
        if (used.get() || System.nanoTime() >= expires) { reply(410, "Setup expired. Start again on the TV."); return }
        if (request[0] == "GET") { reply(200, page()); return }
        if (request[0] != "POST" || headers["origin"]?.let { it != origin } == true ||
            headers["sec-fetch-site"] == "cross-site" || headers.containsKey("transfer-encoding") ||
            headers["content-type"]?.substringBefore(';') != "application/x-www-form-urlencoded") {
            reply(403, "Request rejected"); return
        }
        val length = headers["content-length"]?.toIntOrNull()
        if (length == null || length !in 1..16384) { reply(400, "Invalid form"); return }
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(body, offset, length - offset)
            require(read > 0)
            offset += read
        }
        val fields = body.toString(Charsets.UTF_8).split('&').associate {
            val pair = it.split('=', limit = 2)
            URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
        }
        val details = AssistantPhoneDetails(fields["target"].orEmpty().trim(), fields["token"].orEmpty().trim())
        if (details.target.isBlank() || details.token.isBlank()) { reply(400, "Enter the server and access token. Go back to retry."); return }
        if (!used.compareAndSet(false, true)) { reply(410, "Already submitted"); return }
        reply(200, "<h1>Sent to your TV</h1><p>Select Connect and save on the TV to finish.</p>")
        onReceived(details)
    }

    private fun page(): String {
        val escaped = target.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
        val label = if (local) "Server URL" else "Remote ID"
        return """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>Radio Guaka setup</title></head><body>
            <h1>Connect your TV</h1><p>Use the same trusted Wi-Fi as your TV. This temporary local page uses HTTP.</p>
            <p>In Music Assistant, open Settings → Profile and create an access token. Copy it below.</p>
            <form method="post" action="$path"><p><label>$label<br><input name="target" value="$escaped" required autocomplete="off"></label></p>
            <p><label>Access token<br><input name="token" type="password" required autocomplete="off"></label></p>
            <button type="submit">Send to TV</button></form><p>Then select Connect and save on the TV. This code expires after five minutes.</p></body></html>"""
    }

    override fun close() {
        closed = true
        runCatching { server.close() }
        runCatching { client?.close() }
    }
}
