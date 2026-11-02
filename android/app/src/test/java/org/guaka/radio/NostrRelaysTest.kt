package org.guaka.radio

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class NostrRelaysTest {
    private class TestSigner(val secret: String) : NostrSigner {
        override val pubkey: String = NostrCrypto.publicKey(secret)
        override suspend fun sign(template: JSONObject): JSONObject = NostrCrypto.sign(template, secret)
    }

    private fun client() = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    private fun relayUrl(server: MockWebServer) = server.url("/").toString()
        .replaceFirst("http://", "ws://")

    private fun favoriteEvent(signer: TestSigner, station: String = "station-1"): JSONObject {
        val template = RadiostrFavorites.template(station, "Station", true, 1_800_000_000)
        return NostrCrypto.sign(template, signerSecret(signer))
    }

    // The tests need independent keypairs for valid events from the requested author and an impostor.
    private fun signer() = NostrCrypto.generateSecret()
    private fun signerSecret(signer: TestSigner): String = signer.secret

    @Test
    fun favoritesReturnsOnlyEventsWithRequestedAuthorAndValidSignature() = runBlocking {
        val secret = signer()
        val requested = TestSigner(secret)
        val wrongAuthor = NostrCrypto.sign(
            RadiostrFavorites.template("wrong-author", "Wrong author", true, 1_800_000_001),
            signer(),
        )
        val tampered = favoriteEvent(requested, "tampered").put("content", "forged")
        val valid = favoriteEvent(requested)
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onClosing(webSocket: okhttp3.WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                val request = JSONArray(text)
                if (request.optString(0) != "REQ") return
                val subscription = request.getString(1)
                webSocket.send(JSONArray().put("EVENT").put(subscription).put(wrongAuthor).toString())
                webSocket.send(JSONArray().put("EVENT").put(subscription).put(tampered).toString())
                webSocket.send(JSONArray().put("EVENT").put(subscription).put(valid).toString())
                webSocket.send(JSONArray().put("EOSE").put(subscription).toString())
            }
        }))
        server.start()
        try {
            val events = NostrRelays(client(), listOf(relayUrl(server))).favorites(requested)
            assertEquals(listOf(valid.getString("id")), events.map { it.getString("id") })
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun publishRequiresAnOkForThePublishedEventId() = runBlocking {
        val signer = TestSigner(signer())
        val event = favoriteEvent(signer)
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onClosing(webSocket: okhttp3.WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                val message = JSONArray(text)
                if (message.optString(0) != "EVENT") return
                webSocket.send(JSONArray().put("OK").put("different-event-id").put(true).put("accepted").toString())
                webSocket.close(1000, "test complete")
            }
        }))
        server.start()
        try {
            val result = runCatching { NostrRelays(client(), listOf(relayUrl(server))).publish(event, signer) }
            assertTrue("an unrelated OK must not acknowledge this event", result.isFailure)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun publishSucceedsWhenMatchingOkAcceptsEvent() = runBlocking {
        val signer = TestSigner(signer())
        val event = favoriteEvent(signer)
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onClosing(webSocket: okhttp3.WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                val message = JSONArray(text)
                if (message.optString(0) != "EVENT") return
                webSocket.send(JSONArray().put("OK").put(event.getString("id")).put(true).put("accepted").toString())
            }
        }))
        server.start()
        try {
            NostrRelays(client(), listOf(relayUrl(server))).publish(event, signer)
            assertFalse(server.takeRequest(1, TimeUnit.SECONDS) == null)
        } finally {
            server.shutdown()
        }
    }
}
