package org.guaka.radio

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NostrCryptoTest {
    private val secret = "00".repeat(31) + "01"

    @Test fun generatedSecretHasExpectedLengthAndDerivesPublicKey() {
        val generated = NostrCrypto.generateSecret()
        assertTrue(generated.matches(Regex("[0-9a-f]{64}")))
        assertEquals(64, NostrCrypto.publicKey(generated).length)
    }

    @Test fun signsCanonicalEventAndDetectsMutation() {
        val template = JSONObject()
            .put("created_at", 1_750_000_000L)
            .put("kind", 1)
            .put("tags", JSONArray().put(JSONArray().put("t").put("radioguaka")))
            .put("content", "hello")

        val signed = NostrCrypto.sign(template, secret)
        assertEquals(NostrCrypto.publicKey(secret), signed.getString("pubkey"))
        assertEquals(64, signed.getString("id").length)
        assertEquals(128, signed.getString("sig").length)
        assertEquals(nostrEventId(signed), signed.getString("id"))
        assertTrue(NostrCrypto.verify(signed))

        signed.put("content", "changed")
        assertFalse(NostrCrypto.verify(signed))
    }

    @Test fun rejectsMalformedKeys() {
        val invalid = "0".repeat(64)
        assertFalse(runCatching { NostrCrypto.publicKey(invalid) }.isSuccess)
        assertFalse(runCatching { NostrCrypto.encrypt("x", secret, "bad") }.isSuccess)
    }
}
