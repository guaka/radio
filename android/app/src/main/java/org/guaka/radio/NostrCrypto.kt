package org.guaka.radio

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.EventAssembler
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.crypto.Nip01
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip44Encryption.Nip44
import com.vitorpamplona.quartz.utils.Secp256k1Instance
import org.json.JSONArray
import org.json.JSONObject

/** Thin boundary around Quartz's BIP-340 and NIP-44 implementations. */
object NostrCrypto {
    fun generateSecret(): String = Nip01.privKeyCreate().toHexKey()

    fun publicKey(secret: String): String = KeyPair(secret.toSecretBytes()).pubKey.toHexKey()

    fun sign(template: JSONObject, secret: String): JSONObject {
        val keyPair = KeyPair(secret.toSecretBytes())
        val createdAt = template.getLong("created_at")
        val kind = template.getInt("kind")
        val tagsJson = template.optJSONArray("tags") ?: JSONArray()
        val tags = Array(tagsJson.length()) { i ->
            val row = tagsJson.getJSONArray(i)
            Array(row.length()) { j -> row.getString(j) }
        }
        val content = template.optString("content", "")
        val event = EventAssembler.hashAndSign<Event>(
            pubKey = keyPair.pubKey.toHexKey(),
            createdAt = createdAt,
            kind = kind,
            tags = tags,
            content = content,
            privKey = requireNotNull(keyPair.privKey),
        )
        return JSONObject(event.toJson())
    }

    fun verify(event: JSONObject): Boolean = runCatching {
        Event.fromJson(event.toString()).verify()
    }.getOrDefault(false)

    fun encrypt(plaintext: String, secret: String, peer: String): String =
        Nip44.encrypt(plaintext, secret.toSecretBytes(), peer.toPublicKeyBytes()).encodePayload()

    fun decrypt(ciphertext: String, secret: String, peer: String): String =
        Nip44.decrypt(ciphertext, secret.toSecretBytes(), peer.toPublicKeyBytes())

    private fun String.toSecretBytes(): ByteArray {
        require(matches(Regex("[0-9a-fA-F]{64}"))) { "Secret must be 32-byte hex." }
        val bytes = hexToByteArray()
        require(Secp256k1Instance.isPrivateKeyValid(bytes)) { "Invalid secret key." }
        return bytes
    }

    private fun String.toPublicKeyBytes(): ByteArray {
        require(matches(Regex("[0-9a-fA-F]{64}"))) { "Public key must be 32-byte hex." }
        return hexToByteArray()
    }
}
