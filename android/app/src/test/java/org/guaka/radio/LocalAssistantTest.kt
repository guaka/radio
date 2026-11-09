package org.guaka.radio

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class LocalAssistantTest {
    private fun info(serverId: String = "server-a", version: String = "2.10.9") =
        MockResponse().setBody(JSONObject().put("server_id", serverId).put("server_version", version).toString())

    @Test fun localAssistantUrlsAreNormalizedAndRestricted() {
        listOf(
            "http://localhost:8095" to "http://localhost:8095",
            "http://127.0.0.1:8095/" to "http://127.0.0.1:8095",
            "http://[::1]:8095" to "http://[::1]:8095",
            "http://192.168.1.3:8095" to "http://192.168.1.3:8095",
            "http://music-assistant.local:8095" to "http://music-assistant.local:8095",
            "https://music.example.org:8095/" to "https://music.example.org:8095",
        ).forEach { (input, expected) -> assertEquals(expected, localAssistantUrl(input)) }

        listOf(
            "http://music.example.org:8095", "http://8.8.8.8:8095", "http://192.168.1.3:8095/ingress/ma",
            "http://192.168.1.3:8095/hassio_ingress/token", "https://user:pass@music.example.org",
            "http://192.168.1.3:8095/%2e%2e/api", "http://192.168.1.3:8095\\@evil.example",
            "https://music.example.org/?token=secret", "https://music.example.org/#fragment", "file:///etc/passwd",
        ).forEach { invalid -> assertTrue("Expected rejection: $invalid", runCatching { localAssistantUrl(invalid) }.isFailure) }
    }

    @Test fun infoReadsServerIdentityWithoutPlexHeadersOrFollowingRedirects() = runBlocking {
        val server = MockWebServer().also { it.start() }
        try {
            server.enqueue(info())
            val result = LocalAssistant(ApiClient("test-client")).info("http://localhost:${server.port}/")
            assertEquals(LocalAssistantInfo("server-a", "2.10.9"), result)
            val request = server.takeRequest()
            assertEquals("/info", request.path)
            assertNull(request.getHeader("Authorization"))
            assertNull(request.getHeader("X-Plex-Token"))
            assertNull(request.getHeader("X-Plex-Client-Identifier"))

            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://example.org/collect"))
            assertTrue(runCatching { LocalAssistant(ApiClient("test-client")).info("http://localhost:${server.port}") }.exceptionOrNull() is ApiRequestException)
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun infoRejectsMissingServerIdentityOrVersion() = runBlocking {
        val server = MockWebServer().also { it.start() }
        try {
            server.enqueue(MockResponse().setBody("{}"))
            assertTrue(runCatching { LocalAssistant(ApiClient("test-client")).info("http://localhost:${server.port}") }.exceptionOrNull() is IOException)
            server.enqueue(MockResponse().setBody("""{"server_id":null,"server_version":null}"""))
            assertTrue(runCatching { LocalAssistant(ApiClient("test-client")).info("http://localhost:${server.port}") }.exceptionOrNull() is IOException)
            server.enqueue(MockResponse().setBody("""{"server_id":"server-a"}"""))
            assertTrue(runCatching { LocalAssistant(ApiClient("test-client")).info("http://localhost:${server.port}") }.exceptionOrNull() is IOException)
        } finally { server.shutdown() }
    }

    @Test fun validateChecksExpectedIdentityBeforeSendingTokenAndAuthenticatesPlayersAll() = runBlocking {
        val server = MockWebServer().also { it.start() }
        try {
            val assistant = LocalAssistant(ApiClient("test-client"))
            server.enqueue(info("other-server"))
            assertTrue(runCatching { assistant.validate("http://localhost:${server.port}", "secret", "expected-server") }.exceptionOrNull() is IOException)
            assertEquals(1, server.requestCount)
            server.takeRequest()

            server.enqueue(info())
            server.enqueue(MockResponse().setBody("[]"))
            assertEquals(LocalAssistantInfo("server-a", "2.10.9"), assistant.validate("http://localhost:${server.port}", "secret", "server-a"))
            val infoRequest = server.takeRequest()
            val commandRequest = server.takeRequest()
            assertEquals("/info", infoRequest.path)
            assertEquals("/api", commandRequest.path)
            assertEquals("Bearer secret", commandRequest.getHeader("Authorization"))
            assertEquals("players/all", JSONObject(commandRequest.body.readUtf8()).getString("command"))
            assertNull(commandRequest.getHeader("X-Plex-Token"))
        } finally { server.shutdown() }
    }

    @Test fun validateSurfacesAuthenticationFailureWithoutExposingToken() = runBlocking {
        val server = MockWebServer().also { it.start() }
        try {
            server.enqueue(info())
            server.enqueue(MockResponse().setResponseCode(401))
            val failure = runCatching { LocalAssistant(ApiClient("test-client")).validate("http://localhost:${server.port}", "secret-token") }.exceptionOrNull()
            assertTrue(failure is ApiRequestException)
            assertFalse(failure!!.message.orEmpty().contains("secret-token"))
            server.takeRequest()
            assertEquals("Bearer secret-token", server.takeRequest().getHeader("Authorization"))
        } finally { server.shutdown() }
    }

    @Test fun commandVerifiesServerBeforePostingAndReturnsCommandResult() = runBlocking {
        val server = MockWebServer().also { it.start() }
        try {
            val assistant = LocalAssistant(ApiClient("test-client"))
            server.enqueue(info("wrong-server"))
            assertTrue(runCatching { assistant.command("http://localhost:${server.port}", "secret", "server-a", "players/all") }.exceptionOrNull() is IOException)
            assertEquals(1, server.requestCount)
            server.takeRequest()

            server.enqueue(info())
            server.enqueue(MockResponse().setBody("""{"players":[{"player_id":"speaker-1"}]}"""))
            val args = JSONObject().put("include_unavailable", false)
            val result = assistant.command("http://localhost:${server.port}", "secret", "server-a", "players/all", args)
            assertEquals("speaker-1", (result as JSONObject).getJSONArray("players").getJSONObject(0).getString("player_id"))
            server.takeRequest()
            val request = server.takeRequest()
            assertEquals("Bearer secret", request.getHeader("Authorization"))
            val body = JSONObject(request.body.readUtf8())
            assertEquals("players/all", body.getString("command"))
            assertEquals(false, body.getJSONObject("args").getBoolean("include_unavailable"))
            assertTrue(body.getString("message_id").isNotBlank())
        } finally { server.shutdown() }
    }

    @Test fun commandRejectsApiErrorPayloads() = runBlocking {
        val server = MockWebServer().also { it.start() }
        try {
            server.enqueue(info())
            server.enqueue(MockResponse().setBody("""{"error_code":0,"message":"failed"}"""))
            val failure = runCatching { LocalAssistant(ApiClient("test-client")).command("http://localhost:${server.port}", "secret", "server-a", "players/all") }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertFalse(failure!!.message.orEmpty().contains("secret"))
        } finally { server.shutdown() }
    }

    @Test fun validateRequiresTokenBeforeNetworkAccess() = runBlocking {
        val server = MockWebServer().also { it.start() }
        try {
            assertTrue(runCatching { LocalAssistant(ApiClient("test-client")).validate("http://localhost:${server.port}", " ") }.isFailure)
            assertEquals(0, server.requestCount)
        } finally { server.shutdown() }
    }
}
