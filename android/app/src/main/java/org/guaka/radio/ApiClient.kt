package org.guaka.radio

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class ApiRequestException(val statusCode: Int, val assistant: Boolean = false) : IOException(
    when (statusCode) {
        401, 403 -> if (assistant) "Music Assistant access denied. Check its access token in Setup."
            else "Plex access expired or was removed. Sign in again."
        404, 410 -> "This item is no longer available. Refresh and choose another item."
        408, 429 -> "The server is busy. Wait briefly and try again."
        in 500..599 -> "The server is temporarily unavailable. Try again later."
        else -> "Server returned HTTP $statusCode. Try again."
    }
)

class ApiClient(val clientId: String, val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(5, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).callTimeout(18, TimeUnit.SECONDS)
    .followRedirects(false).followSslRedirects(false).build()) {
    suspend fun request(url: String, token: String = "", body: RequestBody? = null, assistant: Boolean = false): Any {
        val req = Request.Builder().url(url).header("Accept", "application/json")
        if (assistant) req.header("Authorization", "Bearer $token")
        else {
            req.plexHeaders(token)
        }
        if (body != null) req.post(body)
        return suspendCancellableCoroutine { continuation ->
            val call = http.newCall(req.build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            if (!response.isSuccessful) throw ApiRequestException(response.code, assistant)
                            val text = response.body?.string() ?: throw IOException("Server returned an empty response.")
                            val result = JSONTokener(text).nextValue()
                            if (continuation.isActive) continuation.resume(result)
                        } catch (e: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(e)
                        }
                    }
                }
            })
        }
    }

    /** Fetch a bounded image body. The caller supplies an already-validated HTTPS URL. */
    suspend fun imageBytes(url: String, token: String, maxBytes: Int): ByteArray =
        suspendCancellableCoroutine { continuation ->
            val request = Request.Builder().url(url).header("Accept", "image/*").plexHeaders(token).build()
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            if (!response.isSuccessful) throw IOException("Artwork request failed.")
                            if (!response.header("Content-Type").orEmpty().startsWith("image/", ignoreCase = true)) {
                                throw IOException("Plex returned invalid artwork.")
                            }
                            val body = response.body ?: throw IOException("Plex returned empty artwork.")
                            if (body.contentLength() > maxBytes) throw IOException("Artwork is too large.")
                            val input = body.byteStream()
                            val output = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
                            val buffer = ByteArray(8192)
                            var total = 0
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                if (total > maxBytes) throw IOException("Artwork is too large.")
                                output.write(buffer, 0, count)
                            }
                            if (continuation.isActive) continuation.resume(output.toByteArray())
                        } catch (e: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(e)
                        }
                    }
                }
            })
        }

    private fun Request.Builder.plexHeaders(token: String): Request.Builder = apply {
        header("X-Plex-Client-Identifier", clientId).header("X-Plex-Product", "Radio Guaka")
            .header("X-Plex-Platform", "Android").header("X-Plex-Version", BuildConfig.VERSION_NAME)
        if (token.isNotBlank()) header("X-Plex-Token", token)
    }

    suspend fun createPin(): Pin {
        val response = request("https://plex.tv/api/v2/pins", body = FormBody.Builder().add("strong", "true").build()) as JSONObject
        return Pin(response.getInt("id"), response.getString("code"))
    }
    suspend fun pinToken(pin: Pin): String = (request("https://plex.tv/api/v2/pins/${pin.id}") as JSONObject).optString("authToken").let {
        if (it == "null") "" else it
    }
    fun loginUrl(pin: Pin): String {
        val query = "https://app.plex.tv/".toHttpUrl().newBuilder().addQueryParameter("clientID", clientId)
            .addQueryParameter("code", pin.code).addQueryParameter("context[device][product]", "Radio Guaka").build().encodedQuery
        return "https://app.plex.tv/auth#?$query"
    }
    suspend fun servers(token: String): List<PlexServer> = IntegrationParser.servers(
        request("https://plex.tv/api/v2/resources?includeHttps=1&includeRelay=1", token) as JSONArray)

    suspend fun command(base: String, token: String, command: String, args: JSONObject = JSONObject()): Any {
        val payload = JSONObject().put("message_id", java.util.UUID.randomUUID().toString()).put("command", command).put("args", args)
        val response = request("${IntegrationParser.localAssistantUrl(base)}/api", token,
            payload.toString().toRequestBody("application/json".toMediaType()), assistant = true)
        if (response is JSONObject && response.has("error")) throw IOException("Music Assistant rejected the command. Check its API version and permissions.")
        return if (response is JSONObject && response.has("result")) response.get("result") else response
    }
}
