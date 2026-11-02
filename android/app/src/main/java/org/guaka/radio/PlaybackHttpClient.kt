package org.guaka.radio

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Creates per-item clients that keep Plex credentials on the media server's original origin. */
internal object PlaybackHttpClient {
    fun forEntry(base: OkHttpClient, entry: Entry): OkHttpClient {
        val credential = entry.token.takeIf { it.isNotBlank() }
        val origin = if (credential != null) runCatching { entry.url.toHttpUrl() }.getOrElse {
            throw IOException("Invalid Plex media origin.")
        } else null
        return base.newBuilder()
            .followRedirects(credential == null)
            .followSslRedirects(credential == null)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .apply {
                if (credential != null && origin != null) {
                    addInterceptor { chain ->
                        val request = chain.request()
                        if (!request.url.isSameOriginAs(origin)) {
                            throw IOException("Plex media requested a resource outside its server origin.")
                        }
                        chain.proceed(request.newBuilder().header("X-Plex-Token", credential).build())
                    }
                }
            }
            .build()
    }

    private fun HttpUrl.isSameOriginAs(other: HttpUrl): Boolean =
        scheme == other.scheme && host == other.host && port == other.port
}
