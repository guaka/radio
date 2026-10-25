package org.guaka.radio

import android.app.Activity
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

/** Bridges the Android NIP-55 intent transport to the app's NostrSigner API. */
internal class AndroidSignerBridge(
    private val launchIntent: (Intent) -> Unit,
    private val onConnected: (pubkey: String, packageName: String) -> Unit = { _, _ -> },
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
) {
    private val requestMutex = Mutex()

    @Volatile
    private var pending: PendingRequest? = null

    @Volatile
    var connectedPackageName: String? = null
        private set

    init {
        require(timeoutMillis > 0) { "Signer timeout must be positive." }
    }

    /** Opens an installed signer and records the account/package returned by get_public_key. */
    suspend fun connect(): NostrSigner {
        val response = request(type = TYPE_GET_PUBLIC_KEY, payload = "", signerPackage = null)
        val pubkey = validatePubkey(response.result)
        val packageName = validatePackageName(response.packageName)
        connectedPackageName = packageName
        withContext(Dispatchers.Main.immediate) { onConnected(pubkey, packageName) }
        return BoundSigner(pubkey, packageName)
    }

    /** Recreates a previously authorized signer binding without prompting for the public key. */
    fun restore(pubkey: String, packageName: String): NostrSigner {
        val validPubkey = validatePubkey(pubkey)
        val validPackage = validatePackageName(packageName)
        connectedPackageName = validPackage
        return BoundSigner(validPubkey, validPackage)
    }

    /** Completes only the pending request whose NIP-55 correlation ID matches this result. */
    fun onResult(resultCode: Int, data: Intent?) {
        val request = pending ?: return
        val returnedId = runCatching { data?.getStringExtra(EXTRA_ID) }.getOrNull()
        if (resultCode != Activity.RESULT_OK) {
            // NIP-55 failure results may omit data entirely; RESULT_OK responses must correlate.
            if (returnedId != null && returnedId != request.id) return
            request.result.completeExceptionally(IOException("External signer failed the request."))
            return
        }
        // A timed-out request can return after a newer request has started. Ignore stale results.
        if (returnedId != request.id) return
        if (runCatching { data?.getBooleanExtra(EXTRA_REJECTED, false) == true }.getOrDefault(false)) {
            request.result.completeExceptionally(IOException("The signing request was rejected."))
            return
        }

        val value = runCatching { data?.getStringExtra(EXTRA_RESULT) }.getOrNull()
        val event = runCatching { data?.getStringExtra(EXTRA_EVENT) }.getOrNull()
        val packageName = runCatching { data?.getStringExtra(EXTRA_PACKAGE) }.getOrNull()
        if (value.isNullOrBlank() && event.isNullOrBlank()) {
            request.result.completeExceptionally(IOException("External signer returned no result."))
            return
        }
        request.result.complete(SignerResponse(value, event, packageName))
    }

    private suspend fun request(
        type: String,
        payload: String,
        signerPackage: String?,
        currentUser: String? = null,
    ): SignerResponse =
        requestMutex.withLock {
            val id = UUID.randomUUID().toString()
            val deferred = CompletableDeferred<SignerResponse>()
            val request = PendingRequest(id, deferred)
            pending = request
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("$NOSTR_SIGNER_SCHEME:${Uri.encode(payload)}")).apply {
                    signerPackage?.let { setPackage(it) }
                    putExtra(EXTRA_TYPE, type)
                    putExtra(EXTRA_ID, id)
                    currentUser?.let { putExtra(EXTRA_CURRENT_USER, it) }
                }
                withContext(Dispatchers.Main.immediate) { launchIntent(intent) }
                try {
                    withTimeout(timeoutMillis) { deferred.await() }
                } catch (error: TimeoutCancellationException) {
                    throw IOException("External signer did not respond in time.", error)
                }
            } finally {
                if (pending === request) pending = null
            }
        }

    private inner class BoundSigner(override val pubkey: String, private val packageName: String) : NostrSigner {
        override suspend fun sign(template: JSONObject): JSONObject {
            val signableTemplate = JSONObject(template.toString()).put("pubkey", pubkey).apply {
                remove("id")
                remove("sig")
            }
            val response = request(TYPE_SIGN_EVENT, signableTemplate.toString(), packageName, currentUser = pubkey)
            val candidate = parseEvent(response, template, pubkey)
            return validateSignedEvent(template, candidate, pubkey)
        }
    }

    private fun parseEvent(response: SignerResponse, template: JSONObject, pubkey: String): JSONObject {
        val eventJson = response.event?.takeIf { it.isNotBlank() }
            ?: response.result?.takeIf { it.trimStart().startsWith("{") }
        if (eventJson != null) return runCatching { JSONObject(eventJson) }
            .getOrElse { throw IOException("External signer returned malformed event JSON.", it) }

        val signature = response.result?.takeIf { it.isNotBlank() }
            ?: throw IOException("External signer returned no signature.")
        val unsigned = JSONObject(template.toString()).put("pubkey", pubkey).apply {
            remove("id")
            remove("sig")
        }
        return JSONObject(unsigned.toString())
            .put("id", nostrEventId(unsigned))
            .put("sig", signature)
    }

    private data class PendingRequest(val id: String, val result: CompletableDeferred<SignerResponse>)
    private data class SignerResponse(val result: String?, val event: String?, val packageName: String?)

    private fun validatePubkey(value: String?): String {
        val normalized = value?.trim()?.lowercase()
        if (normalized == null || !PUBKEY.matches(normalized)) {
            throw IOException("External signer returned an invalid public key.")
        }
        return normalized
    }

    private fun validatePackageName(value: String?): String {
        val normalized = value?.trim()
        if (normalized == null || !PACKAGE_NAME.matches(normalized)) {
            throw IOException("External signer returned an invalid package name.")
        }
        return normalized
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 120_000L
        const val NOSTR_SIGNER_SCHEME = "nostrsigner"
        const val TYPE_GET_PUBLIC_KEY = "get_public_key"
        const val TYPE_SIGN_EVENT = "sign_event"
        const val EXTRA_TYPE = "type"
        const val EXTRA_ID = "id"
        const val EXTRA_CURRENT_USER = "current_user"
        const val EXTRA_RESULT = "result"
        const val EXTRA_EVENT = "event"
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_REJECTED = "rejected"
        val PUBKEY = Regex("[0-9a-f]{64}")
        val PACKAGE_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")
    }
}
