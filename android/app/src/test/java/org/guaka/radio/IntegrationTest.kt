package org.guaka.radio

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
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
    @Test fun plexBrowsingBuildsPagedTypedRoutesAndEscapesSearchText() {
        val url = IntegrationParser.collectionUrl("https://plex.example:32400", "7", 9, 120, 50, "Miles Davis & John")
            .toHttpUrl()
        assertEquals("/library/sections/7/all", url.encodedPath)
        assertEquals("9", url.queryParameter("type"))
        assertEquals("120", url.queryParameter("X-Plex-Container-Start"))
        assertEquals("50", url.queryParameter("X-Plex-Container-Size"))
        assertEquals("Miles Davis & John", url.queryParameter("title"))
        val children = IntegrationParser.childrenUrl("https://plex.example:32400", "42", 50, 50).toHttpUrl()
        assertEquals("/library/metadata/42/children", children.encodedPath)
        assertEquals("50", children.queryParameter("X-Plex-Container-Start"))
    }
    @Test fun plexParsesArtistsAlbumsAndTracksWithHierarchyAndArtwork() {
        val payload = JSONObject("""{"MediaContainer":{"Metadata":[
          {"type":"artist","ratingKey":"1","title":"Miles Davis","thumb":"/library/metadata/1/thumb/2"},
          {"type":"album","ratingKey":"2","title":"Kind of Blue","parentTitle":"Miles Davis"},
          {"type":"track","ratingKey":"3","title":"So What","parentTitle":"Kind of Blue","grandparentTitle":"Miles Davis","parentRatingKey":"2","grandparentRatingKey":"1"}]}}""")
        val rows = IntegrationParser.mediaEntries(payload, "server-a", "7")
        assertEquals(listOf("artist:server-a:1", "album:server-a:2", "track:server-a:3"), rows.map { it.id })
        assertEquals("Miles Davis", rows[1].subtitle)
        assertEquals("Miles Davis · Kind of Blue", rows[2].subtitle)
        assertEquals("7", rows[2].plexSection)
        assertEquals("2", rows[2].plexParentKey)
        assertEquals("/library/metadata/1/thumb/2", rows[0].artwork)
        assertFalse(rows[1].playable)
        assertTrue(rows[2].playable)
        val unsafeArt = IntegrationParser.mediaEntries(JSONObject("""{"MediaContainer":{"Metadata":[
          {"type":"album","ratingKey":"9","title":"Unsafe","thumb":"/thumb?X-Plex-Token=secret"}]}}"""), "server-a", "7").single()
        assertEquals("", unsafeArt.artwork)
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
            assertEquals(BuildConfig.VERSION_NAME, request.getHeader("X-Plex-Version"))
            assertFalse(request.path!!.contains("private-token"))
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "https://example.org"))
            assertTrue(runCatching { api.request(server.url("/redirect").toString(), "private-token") }.isFailure)
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun artworkUsesPlexHeaderAndRejectsBodiesOverLimit() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val image = byteArrayOf(1, 2, 3, 4)
            server.enqueue(MockResponse().setHeader("Content-Type", "image/jpeg").setBody(okio.Buffer().write(image)))
            val result = ApiClient("test-client").imageBytes(server.url("/thumb").toString(), "private-token", 4)
            assertArrayEquals(image, result)
            assertEquals("private-token", server.takeRequest().getHeader("X-Plex-Token"))
            server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(okio.Buffer().write(ByteArray(5))))
            assertTrue(runCatching { ApiClient("test-client").imageBytes(server.url("/large").toString(), "private-token", 4) }.isFailure)
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
    @Test fun authenticationErrorsRetainServiceAndStatusWithoutRetrying() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val api = ApiClient("test-client")
            server.enqueue(MockResponse().setResponseCode(401))
            val plex = runCatching { api.request(server.url("/library").toString(), "secret") }.exceptionOrNull()
            assertTrue(plex is ApiRequestException)
            assertEquals(401, (plex as ApiRequestException).statusCode)
            assertFalse(plex.assistant)
            assertFalse(plex.message!!.contains("secret"))
            server.enqueue(MockResponse().setResponseCode(403))
            val assistant = runCatching { api.command("http://localhost:${server.port}", "ma-secret", "players/all") }.exceptionOrNull()
            assertTrue(assistant is ApiRequestException)
            assertTrue((assistant as ApiRequestException).assistant)
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }

}
