package org.guaka.radio

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class AndroidNostrSignerTest {
    private val secret = "00".repeat(31) + "01"
    private val pubkey by lazy { NostrCrypto.publicKey(secret) }

    @Test fun connectAndSignatureOnlyResponseUseCorrelatedNip55Intents() = runBlocking {
        val intents = mutableListOf<Intent>()
        lateinit var bridge: AndroidSignerBridge
        bridge = AndroidSignerBridge(launchIntent = { request ->
            intents += request
            val id = request.getStringExtra("id")
            val result = if (request.getStringExtra("type") == "get_public_key") {
                Intent().putExtra("id", id).putExtra("result", pubkey).putExtra("package", SIGNER_PACKAGE)
            } else {
                val template = JSONObject(Uri.decode(request.data!!.schemeSpecificPart))
                val signed = NostrCrypto.sign(template, secret)
                Intent().putExtra("id", id).putExtra("result", signed.getString("sig"))
            }
            bridge.onResult(Activity.RESULT_OK, result)
        })

        val signer = bridge.connect()
        assertEquals(pubkey, signer.pubkey)
        assertEquals(SIGNER_PACKAGE, bridge.connectedPackageName)
        assertEquals(1, intents.size)
        val connect = intents[0]
        assertEquals(Intent.ACTION_VIEW, connect.action)
        assertEquals("nostrsigner", connect.data?.scheme)
        assertEquals("get_public_key", connect.getStringExtra("type"))
        assertNotNull(connect.getStringExtra("id"))
        assertEquals(null, connect.`package`)

        val result = signer.sign(template())
        assertTrue(NostrCrypto.verify(result))
        assertEquals(2, intents.size)
        val signing = intents[1]
        assertEquals(Intent.ACTION_VIEW, signing.action)
        assertEquals("nostrsigner", signing.data?.scheme)
        assertEquals("sign_event", signing.getStringExtra("type"))
        assertEquals(pubkey, signing.getStringExtra("current_user"))
        assertEquals(SIGNER_PACKAGE, signing.`package`)
        assertEquals(pubkey, JSONObject(Uri.decode(signing.data!!.schemeSpecificPart)).getString("pubkey"))
    }

    @Test fun acceptsFullSignedEventResponse() = runBlocking {
        lateinit var bridge: AndroidSignerBridge
        bridge = AndroidSignerBridge(launchIntent = { request ->
            val id = request.getStringExtra("id")
            val result = if (request.getStringExtra("type") == "get_public_key") {
                Intent().putExtra("id", id).putExtra("result", pubkey).putExtra("package", SIGNER_PACKAGE)
            } else {
                val signed = NostrCrypto.sign(JSONObject(Uri.decode(request.data!!.schemeSpecificPart)), secret)
                Intent().putExtra("id", id).putExtra("event", signed.toString())
            }
            bridge.onResult(Activity.RESULT_OK, result)
        })

        val result = bridge.connect().sign(template())
        assertTrue(NostrCrypto.verify(result))
        assertEquals("NIP-55 full event", result.getString("content"))
    }

    @Test fun reportsSignerRejection() = runBlocking {
        lateinit var bridge: AndroidSignerBridge
        bridge = AndroidSignerBridge(launchIntent = { request ->
            val id = request.getStringExtra("id")
            val response = if (request.getStringExtra("type") == "get_public_key") {
                Intent().putExtra("id", id).putExtra("result", pubkey).putExtra("package", SIGNER_PACKAGE)
            } else {
                Intent().putExtra("id", id).putExtra("rejected", true)
            }
            bridge.onResult(Activity.RESULT_OK, response)
        })

        val signer = bridge.connect()
        val error = runCatching { signer.sign(template()) }.exceptionOrNull()
        assertTrue("Expected signer rejection to fail", error is IOException)
        assertTrue(error?.message.orEmpty().contains("rejected"))
    }

    @Test fun rejectsAFullEventThatDoesNotMatchTheRequestedTemplate() = runBlocking {
        lateinit var bridge: AndroidSignerBridge
        bridge = AndroidSignerBridge(launchIntent = { request ->
            val id = request.getStringExtra("id")
            val response = if (request.getStringExtra("type") == "get_public_key") {
                Intent().putExtra("id", id).putExtra("result", pubkey).putExtra("package", SIGNER_PACKAGE)
            } else {
                val changed = NostrCrypto.sign(JSONObject(Uri.decode(request.data!!.schemeSpecificPart)), secret)
                    .put("content", "tampered after signing")
                Intent().putExtra("id", id).putExtra("event", changed.toString())
            }
            bridge.onResult(Activity.RESULT_OK, response)
        })

        val signer = bridge.connect()
        val error = runCatching { signer.sign(template()) }.exceptionOrNull()
        assertTrue("Expected invalid event to fail", error is IllegalArgumentException)
    }

    private fun template() = JSONObject()
        .put("created_at", 1_750_000_000L)
        .put("kind", 1)
        .put("tags", JSONArray().put(JSONArray().put("t").put("radio-guaka")))
        .put("content", "NIP-55 full event")

    private companion object {
        const val SIGNER_PACKAGE = "org.example.nip55signer"
    }
}
