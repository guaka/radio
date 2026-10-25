package org.guaka.radio

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

fun Entry.asMediaItem(): MediaItem = MediaItem.Builder().setMediaId(id).setMediaMetadata(
    MediaMetadata.Builder().setTitle(title).setArtist(subtitle).setIsBrowsable(!playable).setIsPlayable(playable)
        .setMediaType(if (!playable) MediaMetadata.MEDIA_TYPE_FOLDER_MIXED else if (id.startsWith("radio:")) MediaMetadata.MEDIA_TYPE_RADIO_STATION else MediaMetadata.MEDIA_TYPE_MUSIC)
        .build()).build()

@UnstableApi
class PlaybackService : MediaLibraryService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var session: MediaLibrarySession
    private val repository get() = (application as RadioApp).repository

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this).setMediaSourceFactory(ScopedSourceFactory()).setAudioAttributes(AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true).setWakeMode(C.WAKE_MODE_NETWORK).build()
        val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        session = MediaLibrarySession.Builder(this, player, Callback()).setSessionActivity(openApp).build()
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!session.player.playWhenReady) stopSelf()
    }
    override fun onDestroy() {
        scope.cancel()
        session.player.release()
        session.release()
        super.onDestroy()
    }
    private fun <T> future(block: suspend () -> T): ListenableFuture<T> {
        val result = SettableFuture.create<T>()
        val job = scope.launch { try { result.set(block()) } catch (e: Exception) { result.setException(e) } }
        result.addListener({ if (result.isCancelled) job.cancel() }, mainExecutor)
        return result
    }

    // A fresh HTTP client/header scope per source prevents Plex credentials leaking to radio.
    private inner class ScopedSourceFactory : MediaSource.Factory {
        private var drm: DrmSessionManagerProvider? = null
        private var policy: LoadErrorHandlingPolicy? = null
        override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory { drm = provider; return this }
        override fun setLoadErrorHandlingPolicy(value: LoadErrorHandlingPolicy): MediaSource.Factory { policy = value; return this }
        override fun getSupportedTypes() = intArrayOf(C.CONTENT_TYPE_OTHER, C.CONTENT_TYPE_HLS)
        override fun createMediaSource(item: MediaItem): MediaSource {
            val entry = item.localConfiguration?.tag as? Entry ?: error("Unresolved media item")
            val http = OkHttpDataSource.Factory(repository.api.http.newBuilder()
                .followRedirects(entry.token.isBlank()).followSslRedirects(false)
                .callTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS).build())
                .setDefaultRequestProperties(if (entry.token.isBlank()) emptyMap() else mapOf("X-Plex-Token" to entry.token))
            val factory = DefaultMediaSourceFactory(DefaultDataSource.Factory(this@PlaybackService, http))
            drm?.let { factory.setDrmSessionManagerProvider(it) }
            policy?.let { factory.setLoadErrorHandlingPolicy(it) }
            return factory.createMediaSource(item)
        }
    }

    private inner class Callback : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            if (controller.packageName != packageName && !controller.isTrusted) return MediaSession.ConnectionResult.reject()
            return super.onConnect(session, controller)
        }

        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?) =
            Futures.immediateFuture(LibraryResult.ofItem(Entry("root", "Radio Guaka").asMediaItem(), params))

        override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String,
            page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
            try {
                val entries = repository.browse(parentId)
                val start = (page.toLong() * pageSize).coerceAtMost(entries.size.toLong()).toInt()
                LibraryResult.ofItemList(entries.drop(start).take(pageSize).map { it.asMediaItem() }, params)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) {
                LibraryResult.ofError(if (parentId == "plex") androidx.media3.session.SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED
                    else androidx.media3.session.SessionError.ERROR_IO, params)
            }
        }
        override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> = future {
            try { LibraryResult.ofItem(repository.item(mediaId).asMediaItem(), null) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { LibraryResult.ofError(androidx.media3.session.SessionError.ERROR_BAD_VALUE, null) }
        }
        override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>): ListenableFuture<List<MediaItem>> = future {
            // Controllers supply IDs, never trusted URLs or tokens. Resolve each request locally.
            mediaItems.map { requested ->
                val entry = repository.playable(requested.mediaId)
                entry.asMediaItem().buildUpon().setUri(entry.url).setTag(entry).build()
            }
        }
    }
}
