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
import java.util.concurrent.TimeUnit

class ApiClient(val clientId: String, val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(5, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).callTimeout(18, TimeUnit.SECONDS)
    .followRedirects(false).followSslRedirects(false).build()) {
    suspend fun request(url: String, token: String = "", body: RequestBody? = null, assistant: Boolean = false): Any {
        val req = Request.Builder().url(url).header("Accept", "application/json")
        if (assistant) req.header("Authorization", "Bearer $token")
        else {
            req.header("X-Plex-Client-Identifier", clientId).header("X-Plex-Product", "Radio Guaka")
                .header("X-Plex-Platform", "Android").header("X-Plex-Version", "0.1.0")
            if (token.isNotBlank()) req.header("X-Plex-Token", token)
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
                            if (response.code == 401 || response.code == 403) throw IOException("Access denied. Check your sign-in or Music Assistant token.")
                            if (!response.isSuccessful) throw IOException("Server returned HTTP ${response.code}. Try again.")
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
