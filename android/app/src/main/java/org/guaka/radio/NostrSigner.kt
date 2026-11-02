package org.guaka.radio

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

internal interface NostrSigner {
    val pubkey: String
    suspend fun sign(template: JSONObject): JSONObject
}

internal fun nostrEventId(event: JSONObject): String {
    fun quote(value: String) = buildString {
        append('"')
        value.forEach { c -> when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\")
            '\b' -> append("\\b"); '\t' -> append("\\t"); '\n' -> append("\\n")
            '\u000c' -> append("\\f"); '\r' -> append("\\r")
            else -> if (c.code < 32) append("\\u%04x".format(c.code)) else append(c)
        } }
        append('"')
    }
    val tags = event.getJSONArray("tags")
    val encodedTags = (0 until tags.length()).joinToString(",", "[", "]") { i ->
        val tag = tags.getJSONArray(i)
        (0 until tag.length()).joinToString(",", "[", "]") { j -> quote(tag.getString(j)) }
    }
    val encoded = "[0,${quote(event.getString("pubkey"))},${event.getLong("created_at")},${event.getInt("kind")},$encodedTags,${quote(event.getString("content"))}]"
    return MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

internal fun validateSignedEvent(template: JSONObject, event: JSONObject, pubkey: String): JSONObject {
    require(event.getString("pubkey") == pubkey &&
        event.getInt("kind") == template.getInt("kind") &&
        event.getLong("created_at") == template.getLong("created_at") &&
        event.getString("content") == template.getString("content") &&
        event.getJSONArray("tags").toString() == template.getJSONArray("tags").toString() &&
        event.getString("id") == nostrEventId(event) && NostrCrypto.verify(event)) {
        "The signer returned an invalid or changed event."
    }
    return event
}
