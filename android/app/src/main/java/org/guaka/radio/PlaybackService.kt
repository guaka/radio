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
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    private val listening get() = repository.listening
    private var saveJob: kotlinx.coroutines.Job? = null
    private var lastPersisted: SavedPlayback? = null
    private var hasSeenTimeline = false
    private var destroying = false
    private var purgingCredentials = false
    private var artworkJob: kotlinx.coroutines.Job? = null
    private var stopCredentialObserver: (() -> Unit)? = null
    private var requestedArtworkTarget: Pair<String, Int>? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this).setMediaSourceFactory(ScopedSourceFactory()).setAudioAttributes(AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true).setWakeMode(C.WAKE_MODE_NETWORK).build()
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                if (!timeline.isEmpty) hasSeenTimeline = true
                purgeStalePlexItems()
                persistPlayback()
                requestCurrentArtwork()
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                persistPlayback()
                requestCurrentArtwork()
            }
            override fun onPositionDiscontinuity(
                oldPosition: androidx.media3.common.Player.PositionInfo,
                newPosition: androidx.media3.common.Player.PositionInfo,
                reason: Int,
            ) = persistPlayback()
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) startPeriodicSave() else { stopPeriodicSave(); persistPlayback() }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == androidx.media3.common.Player.STATE_READY || playbackState == androidx.media3.common.Player.STATE_ENDED) persistPlayback()
            }
        })
        val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        session = MediaLibrarySession.Builder(this, player, Callback()).setSessionActivity(openApp).build()
        stopCredentialObserver = listening.observePlexChanges {
            scope.launch { purgeStalePlexItems(); persistPlayback() }
        }
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!session.player.playWhenReady) stopSelf()
    }
    override fun onDestroy() {
        stopCredentialObserver?.invoke()
        stopPeriodicSave()
        artworkJob?.cancel()
        persistPlayback()
        destroying = true
        scope.cancel()
        session.player.release()
        session.release()
        super.onDestroy()
    }

    private fun purgeStalePlexItems() {
        if (purgingCredentials || destroying || !::session.isInitialized) return
        val player = session.player
        val generation = listening.plexGeneration()
        val signedIn = repository.credentials.get("plexToken").isNotBlank()
        val stale = (0 until player.mediaItemCount).filter { index ->
            val item = player.getMediaItemAt(index)
            val entry = item.localConfiguration?.tag as? Entry
            item.mediaId.startsWith("track:") && (!signedIn || entry?.credentialGeneration != generation)
        }
        if (stale.isEmpty()) return
        purgingCredentials = true
        try {
            if (player.currentMediaItemIndex in stale) player.pause()
            stale.asReversed().forEach { player.removeMediaItem(it) }
        } finally { purgingCredentials = false }
    }

    private fun startPeriodicSave() {
        if (saveJob?.isActive == true) return
        saveJob = scope.launch {
            while (true) {
                delay(10_000)
                persistPlayback()
            }
        }
    }

    private fun stopPeriodicSave() { saveJob?.cancel(); saveJob = null }

    private fun requestCurrentArtwork() {
        if (!::session.isInitialized) return
        val player = session.player
        val item = player.currentMediaItem
        val id = item?.mediaId
        val index = player.currentMediaItemIndex
        val target = id?.let { it to index }
        if (target == requestedArtworkTarget) return
        requestedArtworkTarget = target
        artworkJob?.cancel()
        artworkJob = null
        if (item == null) return
        val entry = item.localConfiguration?.tag as? Entry ?: return
        if (entry.artwork.isBlank()) return
        artworkJob = scope.launch {
            val bytes = repository.artwork(entry) ?: return@launch
            val thumbnail = kotlinx.coroutines.withContext(Dispatchers.IO) { Artwork.thumbnail(bytes) } ?: return@launch
            val currentPlayer = session.player as? ExoPlayer ?: return@launch
            if (currentPlayer.currentMediaItemIndex != index || currentPlayer.currentMediaItem?.mediaId != id) return@launch
            val currentItem = currentPlayer.getMediaItemAt(index)
            val metadata = currentItem.mediaMetadata.buildUpon()
                .setArtworkData(thumbnail, MediaMetadata.PICTURE_TYPE_FRONT_COVER).build()
            currentPlayer.replaceMediaItem(index, currentItem.buildUpon().setMediaMetadata(metadata).build())
        }
    }

    /** Saves identifiers only. The fresh-service empty timeline must not erase a pending Resume choice. */
    private fun persistPlayback() {
        if (destroying || !::session.isInitialized) return
        val player = session.player
        if (player.mediaItemCount == 0) {
            if (hasSeenTimeline) {
                runCatching { listening.savePlayback(null) }
                lastPersisted = null
                hasSeenTimeline = false
            }
            return
        }
        hasSeenTimeline = true
        val plexAvailable = repository.credentials.get("plexToken").isNotBlank()
        val currentIndex = player.currentMediaItemIndex.coerceIn(0, player.mediaItemCount - 1)
        // Keep a window that always includes the current item when a controller queues over 500 entries.
        val windowStart = if (player.mediaItemCount <= ListeningStore.MAX_QUEUE) 0
            else (currentIndex - ListeningStore.MAX_QUEUE + 1).coerceIn(0, player.mediaItemCount - ListeningStore.MAX_QUEUE)
        val windowEnd = minOf(player.mediaItemCount, windowStart + ListeningStore.MAX_QUEUE)
        val indexed = (windowStart until windowEnd).mapNotNull { index ->
            val id = player.getMediaItemAt(index).mediaId
            val entry = player.getMediaItemAt(index).localConfiguration?.tag as? Entry
            if (!ListeningStore.isStableMediaId(id) || (id.startsWith("track:") &&
                    (!plexAvailable || entry?.credentialGeneration != listening.plexGeneration()))) null else id to index
        }
        if (indexed.isEmpty()) return
        val savedIndex = indexed.indexOfFirst { it.second == currentIndex }
            .takeIf { it >= 0 } ?: indexed.indexOfFirst { it.second > currentIndex }.takeIf { it >= 0 } ?: indexed.lastIndex
        val currentId = indexed[savedIndex].first
        val currentWasRetained = indexed[savedIndex].second == currentIndex
        val state = SavedPlayback(indexed.map { it.first }, savedIndex,
            if (!currentWasRetained || currentId.startsWith("radio:")) 0L else player.currentPosition.coerceAtLeast(0L))
        if (state != lastPersisted) {
            runCatching { listening.savePlayback(state) }.onSuccess { lastPersisted = state }
        }
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
            check(!entry.id.startsWith("track:") || entry.credentialGeneration == listening.plexGeneration()) {
                "Plex sign-in changed. Choose the track again."
            }
            val http = OkHttpDataSource.Factory(repository.api.http.newBuilder()
                .followRedirects(entry.token.isBlank()).followSslRedirects(false)
                .callTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS).build())
                .setDefaultRequestProperties(if (entry.token.isBlank()) emptyMap() else mapOf("X-Plex-Token" to entry.token))
            val factory = DefaultMediaSourceFactory(DefaultDataSource.Factory(this@PlaybackService, http))
            drm?.let { factory.setDrmSessionManagerProvider(it) }
            factory.setLoadErrorHandlingPolicy(policy ?: PlaybackRecovery.loadErrorPolicy())
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
                val entries = repository.browse(parentId, page, pageSize)
                LibraryResult.ofItemList(entries.map { it.asMediaItem() }, params)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) {
                LibraryResult.ofError(if (parentId == "plex") androidx.media3.session.SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED
                    else androidx.media3.session.SessionError.ERROR_IO, params)
            }
        }
        override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> = future {
            try {
                if (mediaId == "favorites") LibraryResult.ofItem(Entry("favorites", "Favorites", "Saved stations and tracks").asMediaItem(), null)
                else LibraryResult.ofItem(repository.item(mediaId).asMediaItem(), null)
            }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { LibraryResult.ofError(androidx.media3.session.SessionError.ERROR_BAD_VALUE, null) }
        }
        override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>): ListenableFuture<List<MediaItem>> = future {
            // Controllers supply IDs, never trusted URLs or tokens. Resolve each request locally.
            val generation = listening.plexGeneration()
            val resolved = coroutineScope {
                val semaphore = Semaphore(4)
                mediaItems.map { requested -> async {
                    semaphore.withPermit {
                        val entry = repository.playable(requested.mediaId)
                        entry.asMediaItem().buildUpon().setUri(entry.url).setTag(entry).build()
                    }
                } }.awaitAll()
            }
            check(generation == listening.plexGeneration()) { "Plex sign-in changed. Choose your queue again." }
            resolved
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = future {
            val generation = listening.plexGeneration()
            val saved = listening.loadPlayback() ?: return@future MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L)
            val tokenAvailable = repository.credentials.get("plexToken").isNotBlank()
            val retained = saved.ids.mapIndexed { index, id -> index to id }
                .filter { (_, id) -> !id.startsWith("track:") || tokenAvailable }
            if (retained.isEmpty()) return@future MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L)
            if (!isForPlayback) {
                val selected = retained.firstOrNull { it.first == saved.index } ?: retained.first()
                val id = selected.second
                val entry = repository.stations.firstOrNull { it.id == id } ?: Entry(id, "Saved Plex track", playable = true)
                return@future MediaSession.MediaItemsWithStartPosition(listOf(entry.asMediaItem()), 0,
                    if (selected.first == saved.index && !id.startsWith("radio:")) saved.positionMs else 0L)
            }
            val resolved = coroutineScope {
                val semaphore = Semaphore(4)
                retained.map { (originalIndex, id) -> async {
                    semaphore.withPermit {
                        try { originalIndex to repository.playable(id) }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (_: Exception) { null }
                    }
                } }.awaitAll().filterNotNull().sortedBy { it.first }
            }
            if (resolved.isEmpty()) return@future MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L)
            val items = resolved.map { (_, entry) -> entry.asMediaItem().buildUpon().setUri(entry.url).setTag(entry).build() }
            val index = resolved.indexOfFirst { it.first == saved.index }
                .takeIf { it >= 0 } ?: resolved.indexOfFirst { it.first > saved.index }.takeIf { it >= 0 } ?: resolved.lastIndex
            val position = if (resolved[index].first != saved.index || items[index].mediaId.startsWith("radio:")) 0L else saved.positionMs
            if (generation != listening.plexGeneration()) MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L)
            else MediaSession.MediaItemsWithStartPosition(items, index, position)
        }
    }
}
