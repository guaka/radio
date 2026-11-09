package org.guaka.radio

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.util.UUID

internal data class LocalAssistantInfo(val serverId: String, val version: String)

/** HTTP API for a user-configured local Music Assistant server. */
internal class LocalAssistant(private val api: ApiClient) {
    suspend fun info(url: String): LocalAssistantInfo {
        val base = localAssistantUrl(url)
        val payload = try {
            api.request("$base/info", assistant = true) as? JSONObject
                ?: throw IOException("Music Assistant returned an invalid server info response.")
        } catch (error: ApiRequestException) {
            throw error
        } catch (error: IOException) {
            throw IOException("Could not reach Music Assistant. Check the URL and network connection.", error)
        }
        val serverId = (payload.opt("server_id") as? String)?.takeIf(String::isNotBlank)
            ?: throw IOException("Music Assistant server info did not include a server ID.")
        val version = (payload.opt("server_version") as? String)?.takeIf(String::isNotBlank)
            ?: throw IOException("Music Assistant server info did not include a version.")
        return LocalAssistantInfo(serverId, version)
    }

    suspend fun validate(url: String, token: String, expectedServerId: String? = null): LocalAssistantInfo {
        require(token.isNotBlank()) { "Enter a Music Assistant access token." }
        val serverInfo = info(url)
        verifyServer(serverInfo, expectedServerId)
        val players = commandOnVerifiedServer(url, token, "players/all", JSONObject())
        if (players !is JSONArray) throw IOException("Music Assistant did not return a player list.")
        return serverInfo
    }

    suspend fun command(url: String, token: String, serverId: String, command: String, args: JSONObject = JSONObject()): Any {
        require(token.isNotBlank()) { "Enter a Music Assistant access token." }
        require(serverId.isNotBlank()) { "Connect to the Music Assistant server before sending commands." }
        require(command.isNotBlank()) { "Music Assistant command is required." }
        val serverInfo = info(url)
        verifyServer(serverInfo, serverId)
        return commandOnVerifiedServer(url, token, command, args)
    }

    private suspend fun commandOnVerifiedServer(url: String, token: String, command: String, args: JSONObject): Any {
        val payload = JSONObject()
            .put("message_id", UUID.randomUUID().toString())
            .put("command", command)
            .put("args", args)
        val response = api.request(
            "${localAssistantUrl(url)}/api",
            token,
            payload.toString().toRequestBody("application/json".toMediaType()),
            assistant = true,
        )
        if (response is JSONObject) {
            if (response.has("error") || response.has("error_code")) {
                throw IOException("Music Assistant rejected the command.")
            }
            if (response.has("result")) return response.get("result")
        }
        return response
    }

    private fun verifyServer(info: LocalAssistantInfo, expectedServerId: String?) {
        if (!expectedServerId.isNullOrBlank() && info.serverId != expectedServerId) {
            throw IOException("Music Assistant server identity changed. Forget the saved connection and connect again.")
        }
    }
}

/** Normalize a root Music Assistant URL and restrict cleartext HTTP to local addresses. */
internal fun localAssistantUrl(raw: String): String {
    val value = raw.trim()
    require(value.isNotEmpty()) { "Enter a Music Assistant server URL." }
    require('\\' !in value) { "Enter a valid Music Assistant server URL." }
    val uri = try { URI(value) } catch (_: Exception) {
        throw IllegalArgumentException("Enter a valid Music Assistant server URL.")
    }
    require(uri.isAbsolute && uri.rawAuthority != null && uri.rawUserInfo == null &&
        (uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) &&
        uri.rawQuery == null && uri.rawFragment == null &&
        (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")) {
        "Use a Music Assistant server root URL without credentials, query parameters, fragments, or ingress paths."
    }
    val parsed = try { value.toHttpUrl() } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("Enter a valid Music Assistant server URL.")
    }
    require(parsed.username.isEmpty() && parsed.password.isEmpty() && parsed.query == null && parsed.fragment == null) {
        "Use a Music Assistant URL without credentials, query parameters, or a fragment."
    }
    // The local server exposes /info and /api at its root; URI validation above rejects paths.
    val host = parsed.host
    val secure = parsed.isHttps
    require(secure || isLocalHttpHost(host)) {
        "Use HTTPS for non-local Music Assistant addresses."
    }
    return parsed.newBuilder().encodedPath("/").build().toString().trimEnd('/')
}

private fun isLocalHttpHost(host: String): Boolean {
    if (host == "localhost" || host.endsWith(".local")) return true
    if (!host.contains(':')) {
        val parts = host.split('.')
        if (parts.size == 4) {
            val octets = parts.map { it.toIntOrNull() ?: return false }
            if (octets.any { it !in 0..255 }) return false
            return octets[0] == 10 || octets[0] == 127 ||
                (octets[0] == 192 && octets[1] == 168) ||
                (octets[0] == 172 && octets[1] in 16..31) ||
                (octets[0] == 169 && octets[1] == 254)
        }
        return !host.contains('.') && host.matches(Regex("[a-zA-Z0-9-]+"))
    }
    return try {
        val address = InetAddress.getByName(host).address
        if (address.size != 16) false
        else InetAddress.getByAddress(address).let { it.isLoopbackAddress || it.isLinkLocalAddress || it.isSiteLocalAddress ||
            ((address[0].toInt() and 0xfe) == 0xfc) }
    } catch (_: Exception) { false }
}
