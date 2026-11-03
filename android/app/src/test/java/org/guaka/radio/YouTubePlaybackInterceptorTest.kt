package org.guaka.radio

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

class YouTubePlaybackInterceptorTest {
    private fun requests(vararg urls: String, range: String? = null): List<Request> {
        val received = mutableListOf<Request>()
        // Stop before DNS/network while exercising OkHttp's request and body builders.
        val client = OkHttpClient.Builder().addInterceptor(YouTubePlaybackInterceptor())
            .addInterceptor { chain ->
                received += chain.request()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body("audio".toResponseBody()).build()
            }.build()
        urls.forEach { url ->
            val request = Request.Builder().url(url).apply { range?.let { header("Range", it) } }.build()
            client.newCall(request).execute().close()
        }
        return received
    }

    @Test fun visionOsMediaUsesMatchingClientAndPostBody() {
        val request = requests("https://rr1.googlevideo.com/videoplayback?c=VISIONOS&sig=signed").single()
        assertEquals("POST", request.method)
        assertEquals("2", request.header("Content-Length"))
        assertEquals(YoutubeParsingHelper.getVisionOsUserAgent(null), request.header("User-Agent"))
        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        assertArrayEquals(byteArrayOf(0x78, 0), buffer.readByteArray())
        assertEquals("signed", request.url.queryParameter("sig"))
    }

    @Test fun seeksPreserveRangeAndAdvanceRequestNumber() {
        val url = "https://rr1.googlevideo.com/videoplayback?c=VISIONOS"
        val requests = requests(url, url, range = "bytes=1048576-2097151")
        assertEquals(listOf("0", "1"), requests.map { it.url.queryParameter("rn") })
        requests.forEach { assertEquals("bytes=1048576-2097151", it.header("Range")) }
        assertEquals("7", requests("$url&rn=7").single().url.queryParameter("rn"))
    }

    @Test fun webMediaUsesBrowserHeaders() {
        val request = requests("https://rr1.googlevideo.com/videoplayback?c=WEB").single()
        assertEquals("https://www.youtube.com", request.header("Origin"))
        assertEquals("https://www.youtube.com", request.header("Referer"))
        assertEquals("cross-site", request.header("Sec-Fetch-Site"))
        assertTrue(request.header("User-Agent")!!.startsWith("Mozilla/5.0"))
    }

    @Test fun unrelatedAndUntrustedUrlsKeepGenericRequests() {
        val urls = arrayOf("https://example.org/videoplayback", "https://rr1.googlevideo.com/other",
            "https://rr1.googlevideo.com.evil.example/videoplayback", "http://rr1.googlevideo.com/videoplayback")
        val requests = requests(*urls)
        requests.forEachIndexed { i, request ->
            assertEquals(urls[i], request.url.toString())
            assertEquals("GET", request.method)
            assertNull(request.header("User-Agent"))
            assertNull(request.body)
        }
    }

    @Test fun mediaClientAppliesYouTubeProtocolOnlyToYouTubeEntries() {
        for (id in listOf("youtube:aqz-KE-bpKQ", "radio:station", "track:server:42")) {
            val client = PlaybackHttpClient.forEntry(OkHttpClient(), Entry(id, "Audio", playable = true))
            assertEquals(if (id.startsWith("youtube:")) 1 else 0,
                client.networkInterceptors.count { it is YouTubePlaybackInterceptor })
            assertEquals(0, client.callTimeoutMillis)
        }
    }

    @Test fun productionClientSendsMediaBodyAndRangeAfterRedirect() {
        val host = "rr1.googlevideo.com"
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName(host).build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer()
        server.useHttps(serverTls.sslSocketFactory(), false)
        server.start()
        try {
            // Keep the real HTTPS hostname/port for production validation, but route sockets
            // to the local fixture. No requests or DNS lookups leave the test process.
            val sockets = object : SocketFactory() {
                override fun createSocket(): Socket = object : Socket() {
                    override fun connect(endpoint: SocketAddress, timeout: Int) {
                        super.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), server.port), timeout)
                    }
                }
                override fun createSocket(h: String, p: Int): Socket = error("unused")
                override fun createSocket(h: String, p: Int, local: InetAddress, lp: Int): Socket = error("unused")
                override fun createSocket(h: InetAddress, p: Int): Socket = error("unused")
                override fun createSocket(h: InetAddress, p: Int, local: InetAddress, lp: Int): Socket = error("unused")
            }
            val url = "https://$host/videoplayback?c=VISIONOS&sig=signed"
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "$url&redirected=1"))
            server.enqueue(MockResponse().setResponseCode(206).setBody("continued audio"))
            val base = OkHttpClient.Builder().socketFactory(sockets)
                .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
                .dns { listOf(InetAddress.getLoopbackAddress()) }.build()
            val entry = Entry("youtube:aqz-KE-bpKQ", "Audio", playable = true, url = url)
            val request = Request.Builder().url(url).header("Range", "bytes=1048576-").build()
            PlaybackHttpClient.forEntry(base, entry).newCall(request).execute().use {
                assertEquals(206, it.code)
                assertEquals("continued audio", it.body.string())
            }
            repeat(2) {
                val received = server.takeRequest(5, TimeUnit.SECONDS) ?: error("Missing media request")
                assertEquals("POST", received.method)
                assertEquals("2", received.getHeader("Content-Length"))
                assertArrayEquals(byteArrayOf(0x78, 0), received.body.readByteArray())
                assertEquals("bytes=1048576-", received.getHeader("Range"))
                assertEquals(YoutubeParsingHelper.getVisionOsUserAgent(null), received.getHeader("User-Agent"))
                assertNull(received.getHeader("X-Plex-Token"))
            }
        } finally { server.shutdown() }
    }
}
