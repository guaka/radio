package org.guaka.radio

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.Closeable

internal object BookstrProtocol {
    const val PACKAGE_NAME = "app.bookstr"
    const val SERVICE_NAME = "app.bookstr.NarrationService"
    const val ROOT_ID = "bookstr-downloaded-books"
    const val EXTRA_FORMAT = "app.bookstr.extra.FORMAT"
    const val EXTRA_PLAYABLE = "app.bookstr.extra.PLAYABLE"
    const val EXTRA_OPEN_READER = "app.bookstr.extra.OPEN_READER"
}

/** A downloaded book exposed by Bookstr's MediaBrowserService. */
internal data class BookstrBook(
    val id: String,
    val title: String,
    val author: String,
    val artUri: String?,
    val format: String,
    val playable: Boolean,
    val openReader: PendingIntent?,
)

/** Compose-friendly snapshot of the Bookstr narration session. */
internal data class BookstrState(
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val error: String? = null,
    val books: List<BookstrBook> = emptyList(),
    val selectedBookId: String? = null,
    val currentBookId: String? = null,
    val currentTitle: String? = null,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val playbackState: Int = PlaybackStateCompat.STATE_NONE,
) {
    val hasActiveBook: Boolean get() = currentBookId != null || selectedBookId != null
}

/**
 * Narrow MediaBrowser client for Bookstr's downloaded-books catalog and TTS session.
 * Playback is always requested by a stable media ID returned by Bookstr; no URL is accepted here.
 */
internal class BookstrController(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private val serviceComponent = ComponentName(BookstrProtocol.PACKAGE_NAME, BookstrProtocol.SERVICE_NAME)

    var state by mutableStateOf(BookstrState())
        private set

    private var browser: MediaBrowserCompat? = null
    private var controller: MediaControllerCompat? = null
    private var controllerCallback: MediaControllerCompat.Callback? = null
    private var catalogSubscriptionCallback: MediaBrowserCompat.SubscriptionCallback? = null
    private var browserGeneration = 0L
    private var closed = false

    fun connect() {
        if (closed || state.connecting || state.connected) return
        try {
            appContext.packageManager.getPackageInfo(BookstrProtocol.PACKAGE_NAME, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            state = state.copy(connecting = false, connected = false, error = "Bookstr is not installed.")
            return
        } catch (_: RuntimeException) {
            // Package visibility can hide the package on older/modified Android builds. The
            // explicit MediaBrowser connection below still gives a clean failure path.
        }

        detachController()
        disconnectBrowser()
        val generation = ++browserGeneration
        state = state.copy(
            connecting = true,
            error = null,
            currentBookId = null,
            currentTitle = null,
            playing = false,
            buffering = false,
            playbackState = PlaybackStateCompat.STATE_NONE,
        )
        val created = MediaBrowserCompat(
            appContext,
            serviceComponent,
            object : MediaBrowserCompat.ConnectionCallback() {
                override fun onConnected() {
                    val activeBrowser = browser ?: return
                    if (closed || generation != browserGeneration || activeBrowser !== createdBrowser) return
                    try {
                        val mediaController = MediaControllerCompat(appContext, activeBrowser.sessionToken)
                        controller = mediaController
                        val callback = object : MediaControllerCompat.Callback() {
                            override fun onPlaybackStateChanged(playbackState: PlaybackStateCompat?) {
                                if (closed || generation != browserGeneration || controller !== mediaController) return
                                updatePlayback(playbackState)
                            }

                            override fun onMetadataChanged(metadata: android.support.v4.media.MediaMetadataCompat?) {
                                if (closed || generation != browserGeneration || controller !== mediaController) return
                                updateMetadata(metadata)
                            }

                            override fun onSessionDestroyed() {
                                if (closed || generation != browserGeneration || controller !== mediaController) return
                                detachController()
                                disconnectBrowser()
                                state = state.copy(connected = false, connecting = false, playing = false,
                                    buffering = false, playbackState = PlaybackStateCompat.STATE_NONE,
                                    error = "Bookstr narration session ended.")
                            }
                        }
                        controllerCallback = callback
                        mediaController.registerCallback(callback)
                        state = state.copy(connected = true, connecting = false, error = null)
                        updatePlayback(mediaController.playbackState)
                        updateMetadata(mediaController.metadata)
                        subscribeCatalog(activeBrowser, generation)
                    } catch (error: RuntimeException) {
                        state = state.copy(connected = false, connecting = false,
                            error = error.message?.takeIf { it.isNotBlank() } ?: "Could not connect to Bookstr.")
                        disconnectBrowser()
                    }
                }

                override fun onConnectionSuspended() {
                    if (generation != browserGeneration || browser !== createdBrowser) return
                    if (!closed) {
                        detachController()
                        state = state.copy(connected = false, connecting = false, playing = false,
                            buffering = false, playbackState = PlaybackStateCompat.STATE_NONE,
                            error = "Bookstr connection was interrupted.")
                    }
                }

                override fun onConnectionFailed() {
                    if (generation != browserGeneration || browser !== createdBrowser) return
                    if (!closed) state = state.copy(connected = false, connecting = false,
                        error = "Could not connect to Bookstr.")
                    disconnectBrowser()
                }
            },
            Bundle(),
        )
        // Used by the callback above to reject callbacks from a replaced browser.
        createdBrowser = created
        browser = created
        try {
            created.connect()
        } catch (error: RuntimeException) {
            state = state.copy(connecting = false, connected = false,
                error = error.message?.takeIf { it.isNotBlank() } ?: "Could not connect to Bookstr.")
            disconnectBrowser()
        }
    }

    private var createdBrowser: MediaBrowserCompat? = null

    /** Refreshes the downloaded catalog after the user returns from Bookstr or retries a failure. */
    fun refreshCatalog() {
        if (closed || !state.connected) return
        val activeBrowser = browser ?: return
        subscribeCatalog(activeBrowser, browserGeneration)
    }

    private fun subscribeCatalog(activeBrowser: MediaBrowserCompat, generation: Long) {
        catalogSubscriptionCallback?.let { old ->
            runCatching { activeBrowser.unsubscribe(BookstrProtocol.ROOT_ID, old) }
        }
        val callback = object : MediaBrowserCompat.SubscriptionCallback() {
            override fun onChildrenLoaded(
                parentId: String,
                children: MutableList<MediaBrowserCompat.MediaItem>,
            ) {
                if (closed || generation != browserGeneration || browser !== activeBrowser ||
                    catalogSubscriptionCallback !== this || parentId != BookstrProtocol.ROOT_ID
                ) return
                state = state.copy(books = children.mapNotNull(::mapBookstrMediaItem).distinctBy { it.id }, error = null)
            }

            override fun onError(parentId: String) {
                if (closed || generation != browserGeneration || browser !== activeBrowser ||
                    catalogSubscriptionCallback !== this || parentId != BookstrProtocol.ROOT_ID
                ) return
                state = state.copy(error = "Bookstr could not load downloaded books.")
            }
        }
        catalogSubscriptionCallback = callback
        state = state.copy(error = null)
        try {
            activeBrowser.subscribe(BookstrProtocol.ROOT_ID, callback)
        } catch (_: RuntimeException) {
            if (catalogSubscriptionCallback === callback) {
                catalogSubscriptionCallback = null
                state = state.copy(error = "Bookstr could not load downloaded books.")
            }
        }
    }

    /** Opens only Bookstr's supplied reader intent for a playable EPUB, from a foreground UI tap. */
    fun play(mediaId: String): Boolean {
        val media = state.books.firstOrNull { it.id == mediaId }
        val pendingIntent = media?.openReader
        if (!state.connected || media == null || !media.playable || pendingIntent == null) {
            state = state.copy(error = "Choose a downloaded EPUB from Bookstr.")
            return false
        }
        if (pendingIntent.creatorPackage != BookstrProtocol.PACKAGE_NAME ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !pendingIntent.isImmutable)) {
            state = state.copy(error = "Bookstr supplied an invalid reader action.")
            return false
        }
        val previousSelection = state.selectedBookId
        state = state.copy(selectedBookId = media.id, error = null)
        val sent = sendControl {
            if (Build.VERSION.SDK_INT >= 34) {
                val options = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    .toBundle()
                pendingIntent.send(appContext, 0, null, null, null, null, options)
            } else {
                pendingIntent.send()
            }
        }
        if (!sent) {
            state = state.copy(selectedBookId = previousSelection)
            return false
        }
        return true
    }

    fun togglePlayPause() {
        val activeController = controller ?: return
        if (!state.connected) return
        if (state.playing) {
            sendControl { activeController.transportControls.pause() }
        } else if (state.currentBookId != null) {
            sendControl { activeController.transportControls.play() }
        }
    }

    fun pause() {
        if (state.connected && state.currentBookId != null) sendControl { controller?.transportControls?.pause() }
    }

    /** Bookstr maps next/previous to narration page navigation. */
    fun skipNext() {
        if (state.connected && state.currentBookId != null) sendControl { controller?.transportControls?.skipToNext() }
    }

    fun skipPrevious() {
        if (state.connected && state.currentBookId != null) sendControl { controller?.transportControls?.skipToPrevious() }
    }

    fun stop() {
        if (state.connected && state.currentBookId != null) sendControl { controller?.transportControls?.stop() }
    }

    override fun close() {
        if (closed) return
        closed = true
        detachController()
        disconnectBrowser()
        state = state.copy(connected = false, connecting = false, playing = false,
            buffering = false, playbackState = PlaybackStateCompat.STATE_NONE)
    }

    private fun updatePlayback(playbackState: PlaybackStateCompat?) {
        val value = playbackState?.state ?: PlaybackStateCompat.STATE_NONE
        state = state.copy(
            playbackState = value,
            playing = value == PlaybackStateCompat.STATE_PLAYING,
            buffering = value == PlaybackStateCompat.STATE_BUFFERING || value == PlaybackStateCompat.STATE_CONNECTING,
            error = playbackState?.errorMessage?.toString()?.takeIf { value == PlaybackStateCompat.STATE_ERROR }
                ?: if (value == PlaybackStateCompat.STATE_ERROR) state.error else null,
        )
    }

    private fun updateMetadata(metadata: android.support.v4.media.MediaMetadataCompat?) {
        val metadataId = metadata?.getString(
            android.support.v4.media.MediaMetadataCompat.METADATA_KEY_MEDIA_ID,
        )?.takeIf { it.isNotBlank() }
        state = state.copy(
            currentBookId = metadataId,
            currentTitle = metadata?.getString(
                android.support.v4.media.MediaMetadataCompat.METADATA_KEY_TITLE,
            )?.takeIf { it.isNotBlank() },
        )
    }

    private inline fun sendControl(control: () -> Unit): Boolean {
        try {
            control()
            return true
        } catch (_: PendingIntent.CanceledException) {
            state = state.copy(error = "Bookstr's reader action is no longer available.")
        } catch (_: RuntimeException) {
            state = state.copy(error = "Bookstr could not perform that playback action.")
        }
        return false
    }

    private fun detachController() {
        val oldController = controller
        val oldCallback = controllerCallback
        if (oldController != null && oldCallback != null) oldController.unregisterCallback(oldCallback)
        controller = null
        controllerCallback = null
    }

    private fun disconnectBrowser() {
        browserGeneration++
        detachController()
        val oldBrowser = browser
        if (oldBrowser != null) {
            catalogSubscriptionCallback?.let { runCatching { oldBrowser.unsubscribe(BookstrProtocol.ROOT_ID, it) } }
            catalogSubscriptionCallback = null
            runCatching { oldBrowser.unsubscribe(BookstrProtocol.ROOT_ID) }
            runCatching { oldBrowser.disconnect() }
        }
        browser = null
        createdBrowser = null
    }

}

/** Parses only Bookstr's stable catalog items and validates the reader capability's origin. */
internal fun mapBookstrMediaItem(item: MediaBrowserCompat.MediaItem): BookstrBook? {
    val description: MediaDescriptionCompat = item.description
    val id = description.mediaId?.takeIf { it.matches(Regex("(?:[0-9a-f]{32}|[0-9a-f]{64})")) } ?: return null
    val extras = description.extras
    val format = extras?.getString(BookstrProtocol.EXTRA_FORMAT)?.takeIf { it.isNotBlank() } ?: "unknown"
    val advertisedPlayable = extras?.getBoolean(BookstrProtocol.EXTRA_PLAYABLE, item.isPlayable) ?: item.isPlayable
    val openReader = extras?.let(::readBookstrReaderPendingIntent)?.takeIf { intent ->
        intent.creatorPackage == BookstrProtocol.PACKAGE_NAME &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || intent.isImmutable)
    }
    val playable = item.isPlayable && advertisedPlayable && format.equals("epub", ignoreCase = true) && openReader != null
    return BookstrBook(
        id = id,
        title = description.title?.toString()?.takeIf { it.isNotBlank() } ?: id,
        author = description.subtitle?.toString().orEmpty(),
        artUri = description.iconUri?.toString(),
        format = format,
        playable = playable,
        openReader = openReader.takeIf { playable },
    )
}

private fun readBookstrReaderPendingIntent(extras: Bundle): PendingIntent? =
    if (Build.VERSION.SDK_INT >= 33) {
        extras.getParcelable(BookstrProtocol.EXTRA_OPEN_READER, PendingIntent::class.java)
    } else {
        @Suppress("DEPRECATION")
        extras.getParcelable(BookstrProtocol.EXTRA_OPEN_READER) as? PendingIntent
    }
