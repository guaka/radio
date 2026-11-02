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
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import java.io.IOException

fun Entry.asMediaItem(): MediaItem = MediaItem.Builder().setMediaId(id).setMediaMetadata(
    MediaMetadata.Builder().setTitle(title).setArtist(subtitle.takeUnless { id.startsWith("radio:") }).setIsBrowsable(!playable).setIsPlayable(playable)
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
    private var selectionJob: kotlinx.coroutines.Job? = null
    private var sleepJob: kotlinx.coroutines.Job? = null
    private var stopSleepObserver: (() -> Unit)? = null
    private val playbackHandoff = PlaybackHandoff()
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build()

    override fun onCreate() {
        super.onCreate()
        val player = buildPlayer(handleAudioFocus = true)
        attachPlaybackListeners(player)
        val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        session = MediaLibrarySession.Builder(this, player, Callback()).setSessionActivity(openApp).build()
        val sleepTimer = (application as RadioApp).sleepTimer
        stopSleepObserver = sleepTimer.observe { scheduleSleepTimer() }
        scheduleSleepTimer()
        stopCredentialObserver = listening.observePlexChanges {
            playbackHandoff.invalidate()
            selectionJob?.cancel()
            scope.launch { purgeStalePlexItems(); persistPlayback() }
        }
    }

    private fun buildPlayer(handleAudioFocus: Boolean): ExoPlayer = ExoPlayer.Builder(this)
        .setMediaSourceFactory(ScopedSourceFactory())
        .setLoadControl(PlaybackBuffering.loadControl())
        .setAudioAttributes(audioAttributes, handleAudioFocus)
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .build()

    private fun attachPlaybackListeners(player: ExoPlayer) {
        player.addListener(object : androidx.media3.common.Player.Listener {
            private var recordedCurrent = false
            private fun recordIfPlaying() {
                if (!recordedCurrent && player.isPlaying) {
                    player.currentMediaItem?.mediaId?.let { id ->
                        runCatching { listening.recordPlayed(id) }.onSuccess { recordedCurrent = true }
                    }
                }
            }
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                if (!isCurrentPlayer(player)) return
                if (!timeline.isEmpty) hasSeenTimeline = true
                purgeStalePlexItems()
                persistPlayback()
                requestCurrentArtwork()
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (!isCurrentPlayer(player)) return
                recordedCurrent = false
                recordIfPlaying()
                persistPlayback()
                requestCurrentArtwork()
            }
            override fun onPositionDiscontinuity(
                oldPosition: androidx.media3.common.Player.PositionInfo,
                newPosition: androidx.media3.common.Player.PositionInfo,
                reason: Int,
            ) { if (isCurrentPlayer(player)) persistPlayback() }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isCurrentPlayer(player)) return
                if (isPlaying) { recordIfPlaying(); startPeriodicSave() } else { stopPeriodicSave(); persistPlayback() }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (!isCurrentPlayer(player)) return
                if (playbackState == androidx.media3.common.Player.STATE_READY || playbackState == androidx.media3.common.Player.STATE_ENDED) persistPlayback()
            }
        })
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!session.player.playWhenReady) stopSelf()
    }
    override fun onDestroy() {
        stopSleepObserver?.invoke()
        sleepJob?.cancel()
        stopCredentialObserver?.invoke()
        stopPeriodicSave()
        artworkJob?.cancel()
        selectionJob?.cancel()
        playbackHandoff.invalidate()
        persistPlayback()
        destroying = true
        scope.cancel()
        session.player.release()
        session.release()
        super.onDestroy()
    }

    private fun scheduleSleepTimer() {
        sleepJob?.cancel()
        val timer = (application as RadioApp).sleepTimer
        val state = timer.state() ?: return
        sleepJob = scope.launch {
            delay((state.deadline - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0))
            sendBroadcast(timer.intent(state.token))
        }
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

    private fun isCurrentPlayer(player: ExoPlayer): Boolean = !::session.isInitialized || session.player === player

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
        if (entry.artwork.isBlank() && !entry.id.startsWith("radio:")) return
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

    private fun selectPlayback(controller: MediaSession.ControllerInfo, args: android.os.Bundle): ListenableFuture<SessionResult> {
        val ids = args.getStringArrayList(PlaybackSelectionCommand.KEY_MEDIA_IDS)?.toList().orEmpty()
        val index = args.getInt(PlaybackSelectionCommand.KEY_START_INDEX, -1)
        val positionMs = args.getLong(PlaybackSelectionCommand.KEY_POSITION_MS, -1L)
        val retrySelectedOnly = args.getBoolean(PlaybackSelectionCommand.KEY_RETRY_SELECTED_ONLY, false)
        val previous = selectionJob
        val request = playbackHandoff.begin()
        previous?.cancel()
        val result = SettableFuture.create<SessionResult>()
        val job = scope.launch {
            var pauseListener: androidx.media3.common.Player.Listener? = null
            val oldPlayer = session.player as? ExoPlayer
            val wasPlaying = oldPlayer?.playWhenReady == true
            try {
                val generation = listening.plexGeneration()
                if (wasPlaying) {
                    pauseListener = object : androidx.media3.common.Player.Listener {
                        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                            if (!playWhenReady && playbackHandoff.isCurrent(request)) {
                                playbackHandoff.invalidate()
                                selectionJob?.cancel()
                            }
                        }
                    }.also(oldPlayer::addListener)
                }
                val reusable = if (retrySelectedOnly) {
                    require(oldPlayer != null && oldPlayer.mediaItemCount == ids.size) {
                        "The current queue is no longer available. Choose it again."
                    }
                    val entries = (0 until oldPlayer.mediaItemCount).map { oldPlayer.getMediaItemAt(it) }
                    require(entries.map { it.mediaId } == ids) { "The current queue changed. Choose it again." }
                    entries.mapIndexedNotNull { itemIndex, item ->
                        if (itemIndex == index) null else (item.localConfiguration?.tag as? Entry)?.let { itemIndex to it }
                    }.toMap()
                } else emptyMap()
                val selection = PlaybackSelection.resolve(
                    ids = ids,
                    index = index,
                    positionMs = positionMs,
                    startingGeneration = generation,
                    currentGeneration = listening::plexGeneration,
                    reuseEntries = reusable,
                    allowUnavailableUnselected = retrySelectedOnly,
                    resolver = repository::playable,
                )
                val mediaItems = selection.entries.map { entry ->
                    entry.asMediaItem().buildUpon().setUri(entry.url).setTag(entry).build()
                }
                val committed = playbackHandoff.stage(
                    request = request,
                    stillValid = { generation == listening.plexGeneration() && session.player === oldPlayer },
                    create = { buildPlayer(handleAudioFocus = false) },
                    prepare = { stagedPlayer ->
                        stagedPlayer.setMediaItems(mediaItems, selection.index, selection.positionMs)
                        stagedPlayer.prepare()
                        withTimeoutOrNull(45_000) { awaitReady(stagedPlayer) }
                            ?: throw IOException("The selected stream took too long to start.")
                    },
                    commit = { stagedPlayer ->
                        // Focus remains with the old player during preload; acquire it at commit.
                        stagedPlayer.setAudioAttributes(audioAttributes, true)
                        attachPlaybackListeners(stagedPlayer)
                        try {
                            session.setPlayer(stagedPlayer)
                            stagedPlayer.play()
                        } finally {
                            if (session.player === stagedPlayer) {
                                if (oldPlayer != null && pauseListener != null) oldPlayer.removeListener(pauseListener!!)
                                oldPlayer?.takeIf { it !== stagedPlayer }?.release()
                            }
                        }
                        runCatching { persistPlayback(); requestCurrentArtwork() }
                    },
                    isAttached = { session.player === it },
                    discard = ExoPlayer::release,
                )
                if (!committed) {
                    if (!result.isCancelled) result.set(SessionResult(SessionError.INFO_CANCELLED))
                    return@launch
                }
                selectionJob = null
                result.set(SessionResult(SessionResult.RESULT_SUCCESS))
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (!result.isCancelled) result.set(SessionResult(SessionError.INFO_CANCELLED))
            } catch (e: Exception) {
                if (!result.isCancelled && playbackHandoff.isCurrent(request)) {
                    val safe = SessionError(LibraryErrors.code(e), LibraryErrors.message(e))
                    result.set(SessionResult(safe))
                    session.sendError(controller, safe)
                } else if (!result.isCancelled) {
                    result.set(SessionResult(SessionError.INFO_CANCELLED))
                }
            } finally {
                if (oldPlayer != null && pauseListener != null) oldPlayer.removeListener(pauseListener!!)
                if (playbackHandoff.isCurrent(request)) selectionJob = null
            }
        }
        selectionJob = job
        result.addListener({ if (result.isCancelled) job.cancel() }, mainExecutor)
        return result
    }

    private suspend fun awaitReady(player: ExoPlayer) = suspendCancellableCoroutine<Unit> { continuation ->
        var done = false
        val listener = object : androidx.media3.common.Player.Listener {
            private fun finish(result: Result<Unit>) {
                if (done || !continuation.isActive) return
                done = true
                player.removeListener(this)
                continuation.resumeWith(result)
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    androidx.media3.common.Player.STATE_READY -> finish(Result.success(Unit))
                    androidx.media3.common.Player.STATE_IDLE -> Unit
                    androidx.media3.common.Player.STATE_ENDED -> finish(Result.failure(IOException("The selected stream ended before it started.")))
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                finish(Result.failure(error))
            }
        }
        player.addListener(listener)
        continuation.invokeOnCancellation { player.removeListener(listener) }
        when {
            player.playerError != null && continuation.isActive -> {
                player.removeListener(listener)
                done = true
                continuation.resumeWith(Result.failure(player.playerError!!))
            }
            player.playbackState == androidx.media3.common.Player.STATE_ENDED && continuation.isActive -> {
                player.removeListener(listener)
                done = true
                continuation.resumeWith(Result.failure(IOException("The selected stream ended before it started.")))
            }
            player.playbackState == androidx.media3.common.Player.STATE_READY && continuation.isActive -> {
                player.removeListener(listener)
                done = true
                continuation.resume(Unit)
            }
        }
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
            val http = OkHttpDataSource.Factory(PlaybackHttpClient.forEntry(repository.api.http, entry))
            val factory = DefaultMediaSourceFactory(DefaultDataSource.Factory(this@PlaybackService, http))
            drm?.let { factory.setDrmSessionManagerProvider(it) }
            factory.setLoadErrorHandlingPolicy(policy ?: PlaybackRecovery.loadErrorPolicy())
            return factory.createMediaSource(item)
        }
    }

    private inner class Callback : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            if (controller.packageName != packageName && !controller.isTrusted) return MediaSession.ConnectionResult.reject()
            val accepted = super.onConnect(session, controller)
            if (controller.packageName != packageName) return accepted
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(PlaybackSelectionCommand.COMMAND)
                .add(SLEEP_COMMAND)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: android.os.Bundle,
        ): ListenableFuture<SessionResult> {
            if (controller.packageName == packageName && customCommand.customAction == PlaybackSelectionCommand.ACTION) {
                return selectPlayback(controller, args)
            }
            if (controller.packageName == packageName && customCommand.customAction == SLEEP_COMMAND.customAction) {
                playbackHandoff.invalidate()
                selectionJob?.cancel()
                session.player.pause()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }

        override fun onPlayerCommandRequest(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            playerCommand: Int,
        ): Int {
            if (playerCommand in setOf(
                    androidx.media3.common.Player.COMMAND_PLAY_PAUSE,
                    androidx.media3.common.Player.COMMAND_STOP,
                    androidx.media3.common.Player.COMMAND_CHANGE_MEDIA_ITEMS,
                    androidx.media3.common.Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                    androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                    androidx.media3.common.Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                )) {
                playbackHandoff.invalidate()
                selectionJob?.cancel()
            }
            return super.onPlayerCommandRequest(session, controller, playerCommand)
        }

        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?) =
            Futures.immediateFuture(LibraryResult.ofItem(Entry("root", "Radio Guaka").asMediaItem(), params))

        override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String,
            page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
            try {
                val entries = repository.browse(parentId, page, pageSize)
                LibraryResult.ofItemList(entries.map { it.asMediaItem() }, params)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { LibraryResult.ofError(LibraryErrors.code(e), params) }
        }
        override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> = future {
            try {
                if (mediaId == "favorites") LibraryResult.ofItem(Entry("favorites", "Favorites", "Saved stations and tracks").asMediaItem(), null)
                else LibraryResult.ofItem(repository.item(mediaId).asMediaItem(), null)
            }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { LibraryResult.ofError(LibraryErrors.code(e), null) }
        }
        override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>): ListenableFuture<List<MediaItem>> = future {
            try {
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
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                mediaSession.sendError(controller, androidx.media3.session.SessionError(LibraryErrors.code(e), LibraryErrors.message(e)))
                throw e
            }
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
                if (generation != listening.plexGeneration())
                    return@future MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L)
                return@future MediaSession.MediaItemsWithStartPosition(listOf(entry.asMediaItem()), 0,
                    if (selected.first == saved.index && !id.startsWith("radio:")) saved.positionMs else 0L)
            }
            val restored = PlaybackResumption.resolve(
                saved = saved,
                tokenAvailable = tokenAvailable,
                startingGeneration = generation,
                currentGeneration = listening::plexGeneration,
                resolver = repository::playable,
            ) ?: return@future MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L)
            val items = restored.entries.map { entry -> entry.asMediaItem().buildUpon().setUri(entry.url).setTag(entry).build() }
            MediaSession.MediaItemsWithStartPosition(items, restored.index, restored.positionMs)
        }
    }
}
