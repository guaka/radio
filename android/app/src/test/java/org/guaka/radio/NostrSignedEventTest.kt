package org.guaka.radio

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NostrSignedEventTest {
    @Test fun acceptsExactSignedTemplateAndCanonicalUnicodeId() {
        val secret = NostrCrypto.generateSecret()
        val template = RadiostrFavorites.template("dronezone", "Drone </radio> \u2028 ⭐", true, 1700000000)
        val signed = NostrCrypto.sign(template, secret)
        assertEquals(signed.getString("id"), nostrEventId(signed))
        assertSame(signed, validateSignedEvent(template, signed, NostrCrypto.publicKey(secret)))
    }
    @Test fun rejectsValidSignatureForAlteredIntentOrWrongAccount() {
        val secret = NostrCrypto.generateSecret()
        val template = RadiostrFavorites.template("dronezone", "Drone", true, 1700000000)
        val altered = JSONObject(template.toString()).put("content", "changed")
        val signed = NostrCrypto.sign(altered, secret)
        assertTrue(runCatching { validateSignedEvent(template, signed, NostrCrypto.publicKey(secret)) }.isFailure)
        assertTrue(runCatching { validateSignedEvent(altered, signed, "0".repeat(64)) }.isFailure)
    }
}
