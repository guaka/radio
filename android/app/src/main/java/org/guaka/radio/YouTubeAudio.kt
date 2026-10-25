package org.guaka.radio

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.IOException
import java.util.concurrent.TimeUnit

internal object YouTubeIds {
    private val videoId = Regex("[A-Za-z0-9_-]{11}")
    fun fromInput(input: String): String? {
        val text = input.trim()
        if (videoId.matches(text)) return text
        val url = text.toHttpUrlOrNull() ?: return null
        if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() || url.port != 443) return null
        val parts = url.pathSegments
        val id = when (url.host) {
            "youtu.be" -> parts.singleOrNull()
            "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com" -> when {
                url.encodedPath == "/watch" -> url.queryParameter("v")
                parts.size == 2 && parts[0] in setOf("shorts", "live", "embed") -> parts[1]
                else -> null
            }
            else -> null
        }
        return id?.takeIf(videoId::matches)
    }
    fun fromMediaId(id: String): String = id.removePrefix("youtube:").also {
        require(id.startsWith("youtube:") && videoId.matches(it)) { "Invalid YouTube video." }
    }
    fun watchUrl(id: String) = "https://www.youtube.com/watch?v=${fromMediaId(id)}"
    fun isAudioHost(url: String): Boolean = url.toHttpUrlOrNull()?.let {
        it.isHttps && it.port == 443 && it.username.isEmpty() && it.password.isEmpty() &&
            it.host.endsWith(".googlevideo.com")
    } == true
}

/** Blocking extractor calls run only on the repository's IO dispatcher. Signed URLs are never saved. */
internal class YouTubeAudio {
    private val service get() = ServiceList.YouTube
    private var query = ""
    private var results = mutableListOf<Entry>()
    private var next: Page? = null
    private var initialized = false
    private var searchedAt = 0L

    @Synchronized fun search(text: String, start: Int, size: Int): List<Entry> {
        if (start >= 1000) return emptyList()
        initialize()
        YouTubeIds.fromInput(text)?.let { return if (start == 0) listOf(item("youtube:$it")) else emptyList() }
        if (text.startsWith("https://") || text.startsWith("http://")) throw IOException("Paste a YouTube video link or search by title.")
        val handler = service.searchQHFactory.fromQuery(text, listOf("videos"), "")
        if (!initialized || query != text || System.nanoTime() - searchedAt > TimeUnit.MINUTES.toNanos(5)) {
            val info = SearchInfo.getInfo(service, handler)
            query = text
            results = info.relatedItems.filterIsInstance<StreamInfoItem>().mapNotNull(::entry).toMutableList()
            next = info.nextPage
            initialized = true
            searchedAt = System.nanoTime()
        }
        var requests = 0
        while (results.size.toLong() < start.toLong() + size && Page.isValid(next) && requests++ < 10) {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
            val page = SearchInfo.getMoreItems(service, handler, next)
            val ids = results.map { it.id }.toHashSet()
            results += page.items.filterIsInstance<StreamInfoItem>().mapNotNull(::entry).filter { ids.add(it.id) }
            next = page.nextPage
        }
        return results.drop(start).take(minOf(size, 1000 - start))
    }

    fun item(id: String): Entry {
        initialize()
        return metadata(id, StreamInfo.getInfo(service, YouTubeIds.watchUrl(id)))
    }

    fun playable(id: String): Entry {
        initialize()
        val info = StreamInfo.getInfo(service, YouTubeIds.watchUrl(id))
        val stream = info.audioStreams.filter {
            it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && YouTubeIds.isAudioHost(it.content)
        }.maxByOrNull { it.averageBitrate }
            ?: throw IOException("This YouTube video has no supported audio-only stream.")
        return metadata(id, info).copy(url = stream.content)
    }

    private fun entry(item: StreamInfoItem): Entry? {
        val id = YouTubeIds.fromInput(item.url) ?: return null
        return Entry("youtube:$id", item.name, item.uploaderName.orEmpty(), true,
            artwork = item.thumbnails.firstOrNull()?.url.orEmpty())
    }
    private fun metadata(id: String, info: StreamInfo) = Entry(id, info.name, info.uploaderName.orEmpty(), true,
        artwork = info.thumbnails.firstOrNull()?.url.orEmpty())

    companion object {
        private var ready = false
        @Synchronized private fun initialize() {
            if (ready) return
            NewPipe.init(object : Downloader() {
                private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
                override fun execute(request: Request): Response {
                    val builder = okhttp3.Request.Builder().url(request.url())
                        .header("User-Agent", "Mozilla/5.0")
                    request.headers().forEach { (key, values) ->
                        builder.removeHeader(key)
                        values.forEach { builder.addHeader(key, it) }
                    }
                    val body = request.dataToSend()?.toRequestBody()
                        ?: if (request.httpMethod() in setOf("POST", "PUT", "PATCH")) ByteArray(0).toRequestBody() else null
                    return client.newCall(builder.method(request.httpMethod(), body).build()).execute().use {
                        Response(it.code, it.message, it.headers.toMultimap(), it.body.string(), it.request.url.toString())
                    }
                }
            })
            ready = true
        }
    }
}
