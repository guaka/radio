package org.guaka.radio

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class IntegrationTest {
    @Test fun discoveryUsesResourceTokenAndHttpsConnections() {
        val servers = IntegrationParser.servers(JSONArray("""[
          {"name":"Plex","clientIdentifier":"server","provides":"server","accessToken":"server-specific-token",
           "connections":[{"uri":"http://192.168.1.3:32400","local":true},
                          {"uri":"https://remote.plex.direct:32400","local":false},
                          {"uri":"https://local.plex.direct:32400","local":true}]},
          {"name":"Player","provides":"player"}]
        """))
        assertEquals(1, servers.size)
        assertEquals("server-specific-token", servers.single().token)
        assertEquals("https://local.plex.direct:32400", servers.single().connections.first().uri)
        assertEquals(2, servers.single().connections.size)
    }
    @Test fun assistantRejectsPublicOrCredentialBearingUrls() {
        listOf("https://example.org", "https://user:password@192.168.1.3:8095", "http://192.168.1.3:8095?token=secret",
            "http://192.168.999.3", "http://8.8.8.8").forEach { value ->
            assertTrue(value, runCatching { IntegrationParser.localAssistantUrl(value) }.isFailure)
        }
        assertEquals("http://192.168.1.20:8095", IntegrationParser.localAssistantUrl("http://192.168.1.20:8095/"))
        assertEquals("http://music-assistant.local:8095", IntegrationParser.localAssistantUrl("http://music-assistant.local:8095"))
    }
    @Test fun tracksHaveStableServerScopedIds() {
        val payload = JSONObject("""{"MediaContainer":{"Metadata":[{"ratingKey":"42","title":"Test","grandparentTitle":"Artist"}]}}""")
        val item = IntegrationParser.tracks(payload, "server-a").single()
        assertEquals("track:server-a:42", item.id)
        assertEquals("Artist", item.subtitle)
        assertTrue(item.playable)
        assertTrue(item.url.isEmpty())
    }
    @Test fun unavailableSpeakersAreFiltered() {
        val speakers = IntegrationParser.speakers(JSONArray("""[
          {"player_id":"living","display_name":"Living room","available":true},
          {"player_id":"missing","available":false}]
        """))
        assertEquals(listOf(Speaker("living", "Living room")), speakers)
    }
    @Test fun plexTokenIsInHeaderAndRedirectsAreNotFollowed() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("{}"))
            val api = ApiClient("test-client")
            api.request(server.url("/library/sections").toString(), "private-token")
            val request = server.takeRequest()
            assertEquals("private-token", request.getHeader("X-Plex-Token"))
            assertFalse(request.path!!.contains("private-token"))
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "https://example.org"))
            assertTrue(runCatching { api.request(server.url("/redirect").toString(), "private-token") }.isFailure)
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun assistantCommandsCarryBearerAndExpectedEnvelope() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("[]"))
            // Local URL policy is tested independently; exercise the request header boundary here.
            ApiClient("test-client").command("http://localhost:${server.port}", "ma-token", "player_queues/play_media",
                JSONObject().put("queue_id", "living").put("media", JSONArray().put("library://track/42")))
            val request = server.takeRequest()
            assertEquals("Bearer ma-token", request.getHeader("Authorization"))
            assertNull(request.getHeader("X-Plex-Token"))
            val body = JSONObject(request.body.readUtf8())
            assertEquals("player_queues/play_media", body.getString("command"))
            assertEquals("living", body.getJSONObject("args").getString("queue_id"))
            assertEquals("library://track/42", body.getJSONObject("args").getJSONArray("media").getString(0))
        } finally { server.shutdown() }
    }
}
