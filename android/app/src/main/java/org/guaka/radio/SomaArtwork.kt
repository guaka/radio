package org.guaka.radio

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

/** Parses the public SomaFM channel catalogue without accepting arbitrary image hosts. */
internal object SomaArtwork {
    private const val HOST = "api.somafm.com"

    fun parse(payload: JSONObject): Map<String, String> {
        val channels = payload.optJSONArray("channels") ?: return emptyMap()
        return buildMap {
            for (i in 0 until channels.length()) {
                val channel = channels.optJSONObject(i) ?: continue
                val id = channel.optString("id")
                if (!id.matches(Regex("[A-Za-z0-9_-]{1,80}"))) continue
                val raw = channel.optString("largeimage").ifBlank { channel.optString("image") }
                val parsed = runCatching { raw.toHttpUrl() }.getOrNull() ?: continue
                if (parsed.isHttps && parsed.port == 443 && parsed.host == HOST && parsed.encodedPath.startsWith("/logos/") &&
                    parsed.username.isEmpty() && parsed.password.isEmpty() && parsed.fragment == null) {
                    put(id, parsed.toString())
                }
            }
        }
    }
}
