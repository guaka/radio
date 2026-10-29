package org.guaka.radio

import org.junit.Assert.*
import org.junit.Test

class AssistantRemoteIdentityTest {
    @Test fun normalizesAndValidatesRemoteIds() {
        assertEquals("AAAAAAAAAAAAAAAAAAAAAAAAAA", AssistantRemoteIdentity.normalize("aaaaaaaa-aaaaa-aaaaa-aaaaaaaa"))
        listOf("", "https://example.org", "A".repeat(25), "0".repeat(26), "A".repeat(25) + "B").forEach {
            assertTrue(runCatching { AssistantRemoteIdentity.normalize(it) }.isFailure)
        }
    }
    @Test fun verifiesEveryAdvertisedCertificateFingerprint() {
        val zero = "a=fingerprint:sha-256 " + List(32) { "00" }.joinToString(":")
        AssistantRemoteIdentity.verify("A".repeat(26), zero)
        assertTrue(runCatching { AssistantRemoteIdentity.verify("A".repeat(26), zero.replaceFirst("00", "01")) }.isFailure)
        assertTrue(runCatching { AssistantRemoteIdentity.verify("A".repeat(26), "$zero\r\na=fingerprint:sha-256 " + List(32) { "01" }.joinToString(":")) }.isFailure)
        assertTrue(runCatching { AssistantRemoteIdentity.verify("A".repeat(26), "v=0") }.isFailure)
        assertTrue(runCatching { AssistantRemoteIdentity.verify("A".repeat(26), zero.replace("sha-256", "sha-1")) }.isFailure)
    }
}

class AssistantRemoteChunksTest {
    @org.junit.Test fun reassemblesOutOfOrderAndRejectsInvalidEnvelopes() {
        val assembler = AssistantRemoteChunks()
        fun frame(seq: Int, text: String) = org.json.JSONObject().put("type", "__chunk__").put("id", 1)
            .put("count", 2).put("seq", seq).put("b64", java.util.Base64.getEncoder().encodeToString(text.toByteArray()))
        org.junit.Assert.assertNull(assembler.accept(frame(1, "[]}")))
        org.junit.Assert.assertEquals(0, assembler.accept(frame(0, "{\"result\":"))!!.getJSONArray("result").length())
        org.junit.Assert.assertTrue(runCatching { assembler.accept(frame(2, "bad")) }.isFailure)
    }
}
