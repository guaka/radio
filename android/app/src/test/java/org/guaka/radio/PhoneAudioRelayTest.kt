package org.guaka.radio

import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class PhoneAudioRelayTest {
    private val certificate = HeldCertificate.Builder()
        .addSubjectAlternativeName("localhost")
        .build()
    private val serverTls = HandshakeCertificates.Builder()
        .heldCertificate(certificate)
        .build()
    private val clientTls = HandshakeCertificates.Builder()
        .addTrustedCertificate(certificate.certificate)
        .build()
    private val sourceClient = OkHttpClient.Builder()
        .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
        .build()
    private val upstreams = mutableListOf<MockWebServer>()
    private val sessions = mutableListOf<PhoneAudioRelaySession>()

    @After fun tearDown() {
        sessions.forEach { it.close() }
        upstreams.forEach { runCatching { it.shutdown() } }
    }

    private fun upstream(): MockWebServer = MockWebServer().also {
        it.useHttps(serverTls.sslSocketFactory(), false)
        it.start()
        upstreams += it
    }

    private fun session(server: MockWebServer, stillValid: () -> Boolean = { true }): PhoneAudioRelaySession =
        PhoneAudioRelaySession(
            entry = Entry(
                id = "track:plex-server:track-1",
                title = "Example",
                playable = true,
                url = server.url("/audio").toString(),
                token = "plex-secret-token",
            ),
            address = InetAddress.getLoopbackAddress(),
            http = sourceClient,
            stillValid = stillValid,
        ).also(sessions::add)

    private data class RawResponse(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
        fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
    }

    /** Uses a plain socket so the relay is exercised as an HTTP server, including its request parser. */
    private fun request(uri: String, method: String = "GET", extraHeaders: List<String> = emptyList()): RawResponse {
        val parsed = java.net.URI(uri)
        Socket(parsed.host, parsed.port).use { socket ->
            socket.soTimeout = 5_000
            val output = socket.getOutputStream()
            output.write("$method ${parsed.rawPath} HTTP/1.1\r\nHost: ${parsed.host}:${parsed.port}\r\nConnection: close\r\n".toByteArray(StandardCharsets.US_ASCII))
            extraHeaders.forEach { output.write("$it\r\n".toByteArray(StandardCharsets.US_ASCII)) }
            output.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
            output.flush()
            val bytes = ByteArrayOutputStream()
            socket.getInputStream().use { input -> input.copyTo(bytes) }
            val response = bytes.toByteArray()
            val separator = "\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
            val split = response.indices.firstOrNull { index ->
                index + separator.size <= response.size && separator.indices.all { response[index + it] == separator[it] }
            } ?: error("Relay returned an incomplete HTTP response")
            val headerText = String(response, 0, split, StandardCharsets.US_ASCII)
            val lines = headerText.split("\r\n")
            val status = lines.first().split(' ')[1].toInt()
            val headers = lines.drop(1).mapNotNull { line ->
                val colon = line.indexOf(':')
                if (colon < 1) null else line.substring(0, colon).trim() to line.substring(colon + 1).trim()
            }.toMap()
            return RawResponse(status, headers, response.copyOfRange(split + separator.size, response.size))
        }
    }

    @Test fun getAndHeadProxyAudioHeadersAndKeepPlexCredentialsOffRelayUrl() {
        val server = upstream()
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/mpeg; charset=binary")
            .setHeader("Content-Length", "6").setHeader("Accept-Ranges", "bytes").setBody("abcdef"))
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/mpeg")
            .setHeader("Content-Length", "6").setHeader("Accept-Ranges", "bytes"))
        val relay = session(server)

        assertFalse(relay.uri.contains("plex-secret-token"))
        assertFalse(relay.uri.contains("localhost"))
        val get = request(relay.uri)
        assertEquals(200, get.status)
        assertTrue(get.header("Content-Type")!!.startsWith("audio/mpeg"))
        assertEquals("6", get.header("Content-Length"))
        assertEquals("bytes", get.header("Accept-Ranges"))
        assertEquals("abcdef", String(get.body, StandardCharsets.UTF_8))
        assertFalse(get.headers.toString().plus(String(get.body, StandardCharsets.UTF_8)).contains("plex-secret-token"))
        assertEquals("plex-secret-token", server.takeRequest(5, TimeUnit.SECONDS)?.getHeader("X-Plex-Token"))

        val head = request(relay.uri, method = "HEAD")
        assertEquals(200, head.status)
        assertEquals("6", head.header("Content-Length"))
        assertTrue(head.body.isEmpty())
        val headRequest = server.takeRequest(5, TimeUnit.SECONDS)
        assertEquals("HEAD", headRequest?.method)
        assertEquals("plex-secret-token", headRequest?.getHeader("X-Plex-Token"))
    }

    @Test fun rangeRequestsPreservePartialAndUnsatisfiedRangeHeaders() {
        val server = upstream()
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Type", "audio/mp4")
            .setHeader("Content-Length", "3").setHeader("Content-Range", "bytes 2-4/6")
            .setHeader("Accept-Ranges", "bytes").setBody("cde"))
        server.enqueue(MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */6")
            .setBody("upstream range error"))
        val relay = session(server)

        val partial = request(relay.uri, extraHeaders = listOf("Range: bytes=2-4"))
        assertEquals(206, partial.status)
        assertEquals("bytes 2-4/6", partial.header("Content-Range"))
        assertEquals("3", partial.header("Content-Length"))
        assertEquals("bytes", partial.header("Accept-Ranges"))
        assertEquals("cde", String(partial.body, StandardCharsets.UTF_8))
        assertEquals("bytes=2-4", server.takeRequest(5, TimeUnit.SECONDS)?.getHeader("Range"))

        val unsatisfied = request(relay.uri, extraHeaders = listOf("Range: bytes=99-"))
        assertEquals(416, unsatisfied.status)
        assertEquals("bytes */6", unsatisfied.header("Content-Range"))
        assertEquals("0", unsatisfied.header("Content-Length"))
        assertTrue(unsatisfied.body.isEmpty())
        assertEquals("bytes=99-", server.takeRequest(5, TimeUnit.SECONDS)?.getHeader("Range"))
    }

    @Test fun invalidCapabilityAndRevokedGenerationReturn404WithoutContactingUpstream() {
        val server = upstream()
        val valid = session(server)
        val invalid = request(valid.uri.substringBeforeLast('/') + "/not-the-capability")
        assertEquals(404, invalid.status)
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))

        val current = java.util.concurrent.atomic.AtomicBoolean(true)
        val revoked = session(server) { current.get() }
        current.set(false)
        val response = request(revoked.uri)
        assertEquals(404, response.status)
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
    }

    @Test fun plexTokenIsNeverForwardedToASecondOriginOrReturnedToReceiver() {
        val origin = upstream()
        val otherOrigin = upstream()
        origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", otherOrigin.url("/steal")))
        val relay = session(origin)

        val response = request(relay.uri)
        assertEquals(502, response.status)
        assertFalse(relay.uri.contains(origin.url("/audio").host))
        assertFalse(String(response.body, StandardCharsets.UTF_8).contains("plex-secret-token"))
        assertEquals("plex-secret-token", origin.takeRequest(5, TimeUnit.SECONDS)?.getHeader("X-Plex-Token"))
        assertNull(otherOrigin.takeRequest(100, TimeUnit.MILLISECONDS))
    }

    @Test fun closeStopsAcceptingRelayRequests() {
        val relay = session(upstream())
        val address = java.net.URI(relay.uri)
        relay.close()
        sessions.remove(relay)

        val connection = runCatching { Socket(address.host, address.port) }.getOrNull()
        assertNull("closed relay listener must reject new connections", connection)
        assertFalse(relay.isOpen)
    }
}
