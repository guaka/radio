package org.guaka.radio

import okhttp3.Interceptor
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.util.concurrent.atomic.AtomicLong

/** Match NewPipe's media requests, including requests made after seeking or redirects. */
internal class YouTubePlaybackInterceptor : Interceptor {
    private val requestNumber = AtomicLong()

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        // Only alter extracted YouTube media, never unrelated hosts reached by a redirect.
        if (!YouTubeIds.isAudioHost(original.url.toString()) || original.url.encodedPath != "/videoplayback") {
            return chain.proceed(original)
        }
        val url = original.url.newBuilder().apply {
            if (original.url.queryParameter("rn") == null) {
                addQueryParameter("rn", requestNumber.getAndIncrement().toString())
            }
        }.build()
        val request = original.newBuilder().url(url)
            // Some extracted clients require POST rather than a generic player GET.
            // See NewPipe's YoutubeHttpDataSource; preserve Media3's Range for seeks.
            .post(byteArrayOf(0x78, 0).toRequestBody())
            // Network interceptors run after OkHttp has prepared the original GET headers.
            .header("Content-Length", "2")
            .removeHeader("Transfer-Encoding")
            .header("User-Agent", if (url.queryParameter("c") == "VISIONOS") {
                YoutubeParsingHelper.getVisionOsUserAgent(null)
            } else {
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36"
            })
            .header("TE", "trailers")
        if (url.queryParameter("c")?.startsWith("WEB") == true) {
            request.header("Origin", "https://www.youtube.com")
                .header("Referer", "https://www.youtube.com")
                .header("Sec-Fetch-Dest", "empty")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Site", "cross-site")
        }
        return chain.proceed(request.build())
    }
}
