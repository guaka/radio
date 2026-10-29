package org.guaka.radio

import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackHttpClientTest {
    @Test fun plexTokenIsSentToSameOriginChildResources() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("segment"))
            val entry = Entry("track:server:42", "Track", playable = true,
                url = server.url("/plex/master.m3u8").toString(), token = "secret-plex-token")
            val request = Request.Builder().url(server.url("/plex/segment.ts")).build()

            PlaybackHttpClient.forEntry(OkHttpClient(), entry).newCall(request).execute().use { response ->
                assertTrue(response.isSuccessful)
            }
            val received = server.takeRequest()
            assertEquals("secret-plex-token", received.getHeader("X-Plex-Token"))
            assertEquals("/plex/segment.ts", received.path)
        } finally { server.shutdown() }
    }

    @Test fun plexClientDoesNotFollowCredentialBearingRedirects() {
        val origin = MockWebServer()
        val redirectTarget = MockWebServer()
        origin.start(); redirectTarget.start()
        try {
            origin.enqueue(MockResponse().setResponseCode(302).addHeader("Location", redirectTarget.url("/stream").toString()))
            val entry = Entry("track:server:42", "Track", playable = true,
                url = origin.url("/plex/master.m3u8").toString(), token = "secret-plex-token")

            PlaybackHttpClient.forEntry(OkHttpClient(), entry)
                .newCall(Request.Builder().url(entry.url).build()).execute().use { response ->
                    assertEquals(302, response.code)
                }
            assertEquals("secret-plex-token", origin.takeRequest().getHeader("X-Plex-Token"))
            assertEquals(0, redirectTarget.requestCount)
        } finally { origin.shutdown(); redirectTarget.shutdown() }
    }

    @Test fun plexTokenIsNeverSentToForeignHostOrPort() {
        val origin = MockWebServer()
        val foreignPort = MockWebServer()
        origin.start(); foreignPort.start()
        try {
            val entry = Entry("track:server:42", "Track", playable = true,
                url = origin.url("/plex/master.m3u8").toString(), token = "secret-plex-token")
            val client = PlaybackHttpClient.forEntry(OkHttpClient(), entry)
            val originUrl = origin.url("/segment.ts")
            val otherLoopbackHost = if (originUrl.host == "localhost") "127.0.0.1" else "localhost"
            val foreignHostRequest = Request.Builder().url(originUrl.newBuilder().host(otherLoopbackHost).build()).build()
            val foreignPortRequest = Request.Builder().url(originUrl.newBuilder().port(foreignPort.port).build()).build()
            val foreignSchemeRequest = Request.Builder().url(originUrl.newBuilder().scheme("https").port(originUrl.port).build()).build()

            val hostError = runCatching { client.newCall(foreignHostRequest).execute() }.exceptionOrNull()
            val portError = runCatching { client.newCall(foreignPortRequest).execute() }.exceptionOrNull()
            val schemeError = runCatching { client.newCall(foreignSchemeRequest).execute() }.exceptionOrNull()
            assertTrue(hostError is IOException)
            assertTrue(portError is IOException)
            assertTrue(schemeError is IOException)
            assertFalse(hostError!!.message.orEmpty().contains("secret-plex-token"))
            assertEquals(0, origin.requestCount)
            assertEquals(0, foreignPort.requestCount)
        } finally {
            origin.shutdown(); foreignPort.shutdown()
        }
    }

    @Test fun tokenlessRadioClientFollowsRedirectsWithoutPlexCredentials() {
        val radio = MockWebServer()
        val redirected = MockWebServer()
        radio.start(); redirected.start()
        try {
            radio.enqueue(MockResponse().setResponseCode(302).addHeader("Location", redirected.url("/stream").toString()))
            redirected.enqueue(MockResponse().setBody("radio audio"))
            val entry = Entry("radio:station", "Station", playable = true, url = radio.url("/listen").toString())

            PlaybackHttpClient.forEntry(OkHttpClient(), entry).newCall(Request.Builder().url(entry.url).build())
                .execute().use { response -> assertTrue(response.isSuccessful) }
            assertNull(radio.takeRequest().getHeader("X-Plex-Token"))
            assertNull(redirected.takeRequest().getHeader("X-Plex-Token"))
        } finally { radio.shutdown(); redirected.shutdown() }
    }
}
