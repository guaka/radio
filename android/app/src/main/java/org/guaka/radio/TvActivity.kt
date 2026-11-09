@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package org.guaka.radio

import android.content.ComponentName
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Separate launcher and layout for Google TV; playback stays in the shared service. */
class TvActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF00D8D0))) {
                Surface(Modifier.fillMaxSize()) { TvScreen() }
            }
        }
    }
}

@Composable
internal fun TvScreen(model: MainModel = viewModel()) {
    val context = LocalContext.current
    var browser by remember { mutableStateOf<MediaBrowser?>(null) }
    var current by remember { mutableStateOf<MediaItem?>(null) }
    var playing by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var selectionEpoch by remember { mutableIntStateOf(0) }
    var albumShuffleActive by remember { mutableStateOf(false) }
    var showSignIn by remember { mutableStateOf(false) }
    var showOutputs by remember { mutableStateOf(false) }
    val remote = model.playbackOutputRoute.isSpeakers
    val outputItem = if (remote) model.activeRemoteEntry?.asMediaItem() else current
    LaunchedEffect(model.output) {
        if (remote) while (true) {
            model.refreshSpeakerProgress()
            model.refreshSpeakerVolumes()
            delay(8_000)
        }
    }
    fun selectLocal(ids: List<String>, index: Int = 0, positionMs: Long = 0L) {
        val player = browser ?: return
        val epoch = ++selectionEpoch
        selecting = true
        error = ""
        model.preparePhoneOutput(onReady = {
            if (epoch != selectionEpoch) return@preparePhoneOutput
            val result = player.sendCustomCommand(PlaybackSelectionCommand.COMMAND,
                PlaybackSelectionCommand.bundle(ids, index, positionMs))
            result.addListener({
                if (epoch == selectionEpoch) {
                    selecting = false
                    val outcome = runCatching { result.get() }.getOrNull()
                    if (outcome?.resultCode == SessionResult.RESULT_SUCCESS) model.phoneOutput()
                    else {
                        model.resumeSpeakerOutputAfterFailure()
                        error = outcome?.sessionError?.message ?: "Could not play this item. Choose it again to retry."
                    }
                }
            }, context.mainExecutor)
        }, onFailure = {
            if (epoch == selectionEpoch) {
                selecting = false
                error = "Could not pause speaker playback. Try again."
            }
        })
    }
    DisposableEffect(context) {
        var attached: MediaBrowser? = null
        val playerListener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                current = player.currentMediaItem
                playing = player.isPlaying
                buffering = player.playbackState == Player.STATE_BUFFERING
            }
            override fun onPlayerError(failure: androidx.media3.common.PlaybackException) {
                error = PlaybackRecovery.guidance(PlaybackRecovery.classify(failure, failure.errorCode))
            }
        }
        val browserListener = object : MediaBrowser.Listener {
            override fun onExtrasChanged(controller: androidx.media3.session.MediaController, extras: Bundle) {
                albumShuffleActive = extras.containsKey(PlaybackSelectionCommand.KEY_ALBUM_SCOPE)
            }
            override fun onError(controller: androidx.media3.session.MediaController, sessionError: androidx.media3.session.SessionError) {
                error = sessionError.message ?: "Playback command failed."
            }
        }
        val future = MediaBrowser.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java)))
            .setListener(browserListener).buildAsync()
        var disposed = false
        future.addListener({
            if (!disposed) runCatching { future.get() }.onSuccess {
                attached = it
                browser = it
                albumShuffleActive = it.sessionExtras.containsKey(PlaybackSelectionCommand.KEY_ALBUM_SCOPE)
                current = it.currentMediaItem
                playing = it.isPlaying
                buffering = it.playbackState == Player.STATE_BUFFERING
                it.addListener(playerListener)
            }.onFailure { error = "Could not connect to playback. Reopen the app." }
        }, context.mainExecutor)
        onDispose {
            disposed = true
            attached?.removeListener(playerListener)
            MediaBrowser.releaseFuture(future)
        }
    }
    BackHandler(enabled = model.canGoBack) { model.back() }
    LaunchedEffect(model.plexSignedIn) { if (model.plexSignedIn) showSignIn = false }
    TvContent(
        entries = tvSearchEntries(model.searchTerm, model.entries, model.searchResults),
        title = if (model.searchTerm.isBlank()) model.locationTitle else "Search results", favorites = model.favorites,
        locationId = model.parent, browseKey = "${model.parent}|${model.searchTerm}",
        albumShuffleActive = albumShuffleActive,
        albumShuffleEnabled = !remote && browser != null && !selecting,
        onShuffleAlbums = { scope ->
            val player = browser
            if (player != null && !remote) {
                val epoch = ++selectionEpoch
                selecting = true
                error = ""
                model.preparePhoneOutput(onReady = {
                    if (epoch != selectionEpoch) return@preparePhoneOutput
                    val result = player.sendCustomCommand(PlaybackSelectionCommand.COMMAND, Bundle().apply {
                        putString(PlaybackSelectionCommand.KEY_ALBUM_SCOPE, scope)
                    })
                    result.addListener({
                        if (epoch == selectionEpoch) {
                            selecting = false
                            val outcome = runCatching { result.get() }.getOrNull()
                            if (outcome?.resultCode == SessionResult.RESULT_SUCCESS) {
                                model.phoneOutput()
                                albumShuffleActive = true
                            } else {
                                model.resumeSpeakerOutputAfterFailure()
                                error = outcome?.sessionError?.message ?: "Could not start album shuffle. Try again."
                            }
                        }
                    }, context.mainExecutor)
                }, onFailure = {
                    if (epoch == selectionEpoch) {
                        selecting = false
                        error = "Could not pause speaker playback. Try again."
                    }
                })
            }
        },
        onStopAlbumShuffle = {
            browser?.sendCustomCommand(PlaybackSelectionCommand.STOP_SHUFFLE_COMMAND, Bundle.EMPTY)
                ?.addListener({ albumShuffleActive = false }, context.mainExecutor)
        },
        currentTitle = outputItem?.mediaMetadata?.title?.toString().orEmpty(),
        currentArtist = outputItem?.mediaMetadata?.artist?.toString(),
        currentStation = outputItem?.mediaMetadata?.station?.toString(),
        currentId = outputItem?.mediaId, playing = if (remote) model.speakerProgress?.playing == true else playing,
        ready = if (remote) model.speakerProgress != null else browser != null,
        busy = model.busy || selecting || buffering || model.searchingSources.isNotEmpty(),
        status = error.ifBlank { if (model.searchTerm.isBlank()) model.status
            else model.searchErrors.entries.joinToString(" · ") { "${it.key}: ${it.value}" } },
        hasMore = model.searchTerm.isBlank() && model.hasMore, canGoBack = model.canGoBack, listEpoch = model.listEpoch,
        search = model.searchTerm, signedIn = model.plexSignedIn,
        onSearch = model::updateFilter, onSource = { model.browse(it); error = "" },
        onBack = model::back, onMore = { if (model.paginationFailed) model.retryLoadMore() else model.loadMore() },
        onFavorite = model::favorite,
        paginationFailed = model.paginationFailed, accountName = model.plexAccountName,
        loadArtwork = { model.repository.artwork(it) },
        loadCurrentArtwork = { id -> model.repository.artwork(model.repository.item(id)) },
        onSignIn = { showSignIn = true; model.signIn(onTv = true) }, onSignOut = model::signOut,
        outputName = if (remote) model.output else "This TV",
        onOutputs = { showOutputs = true; model.speakerPicker(outputItem) },
        connectionSettings = { TvAssistantSettings(model) },
        onToggle = { if (remote) model.toggleSpeakers() else browser?.let { if (it.isPlaying) it.pause() else { it.prepare(); it.play() } } },
        onPlay = { if (remote) model.setSpeakersPlaying(true) else browser?.play() },
        onPause = { if (remote) model.setSpeakersPlaying(false) else browser?.pause() },
        onEntry = { entry ->
            if (!entry.playable) model.browse(entry.id, title = entry.title)
            else if (remote) {
                ++selectionEpoch
                selecting = false
                error = ""
                model.selectOnCurrentSpeakers(entry.id) { browser?.pause() }
            } else {
                val epoch = ++selectionEpoch
                val albumParent = if (model.searchTerm.isBlank()) model.parent else ""
                val browseEpoch = model.listEpoch
                val query = model.searchTerm
                model.action {
                    val queue = AlbumTrackQueueBuilder.resolve(albumParent, entry, browse = model.repository::browse)
                    if (epoch == selectionEpoch && browseEpoch == model.listEpoch && query == model.searchTerm)
                        selectLocal(queue?.ids ?: listOf(entry.id), queue?.index ?: 0)
                }
            }
        },
    )
    if (showOutputs) TvSpeakerDialog(
        speakers = model.speakers, selected = model.activeSpeakerSelection.map { it.id }.toSet(),
        volumes = model.speakerVolumes, busy = model.busy, status = model.status,
        progress = model.speakerProgress,
        onDismiss = { showOutputs = false; model.dismissSpeakers() },
        onSelect = { targets -> showOutputs = false; model.selectSpeakers(targets) { browser?.pause() } },
        onLocal = {
            showOutputs = false
            val id = outputItem?.mediaId
            model.dismissSpeakers()
            if (id != null) selectLocal(listOf(id), positionMs = if (id.startsWith("radio:")) 0L else model.speakerProgress?.positionAt(System.nanoTime() / 1_000_000) ?: 0L)
        },
        onVolume = model::setSpeakerVolume, onSeek = model::seekSpeakers,
    )
    model.speakerTracks?.let { tracks ->
        AlertDialog(onDismissRequest = model::dismissSpeakers, title = { Text("Confirm Music Assistant track") },
            text = { Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                if (tracks.isEmpty()) Text(model.status)
                tracks.forEach { track -> TvButton("${track.name} — ${track.artist}", onClick = {
                    model.playOnSpeaker(track.uri) { browser?.pause() }
                }) }
            } }, confirmButton = { TvButton("Cancel", onClick = model::dismissSpeakers) })
    }
    BackHandler(enabled = showSignIn) { showSignIn = false; model.cancelTvSignIn() }
    if (showSignIn) AlertDialog(
        onDismissRequest = { showSignIn = false; model.cancelTvSignIn() },
        title = { Text("Sign in to Plex") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("On your phone or computer, open plex.tv/link and enter:")
            model.tvPlexCode?.let { Text(it, style = MaterialTheme.typography.displayMedium) }
            Text(model.status)
        } },
        confirmButton = { TvButton("New code", onClick = { model.signIn(onTv = true) }) },
        dismissButton = { TvButton("Cancel", onClick = { showSignIn = false; model.cancelTvSignIn() }) },
    )
}

@Composable
internal fun TvContent(
    entries: List<Entry>, title: String, favorites: Set<String>, currentTitle: String, currentId: String?,
    playing: Boolean, ready: Boolean, busy: Boolean, status: String, hasMore: Boolean, canGoBack: Boolean,
    listEpoch: Int, search: String, signedIn: Boolean,
    onSearch: (String) -> Unit, onSource: (String) -> Unit, onBack: () -> Unit, onMore: () -> Unit,
    onFavorite: (String) -> Unit, onSignIn: () -> Unit, onSignOut: () -> Unit,
    onToggle: () -> Unit, onPlay: () -> Unit, onPause: () -> Unit, onEntry: (Entry) -> Unit,
    currentArtist: String? = null, currentStation: String? = null,
    locationId: String = "", browseKey: String = title,
    albumShuffleActive: Boolean = false, albumShuffleEnabled: Boolean = false,
    onShuffleAlbums: (String) -> Unit = {}, onStopAlbumShuffle: () -> Unit = {},
    paginationFailed: Boolean = false, accountName: String = "",
    outputName: String = "This TV", onOutputs: () -> Unit = {},
    connectionSettings: @Composable () -> Unit = {},
    loadArtwork: suspend (Entry) -> ByteArray? = { null },
    loadCurrentArtwork: suspend (String) -> ByteArray? = { null },
) {
    val firstFocus = remember { FocusRequester() }
    val settingsFocus = remember { FocusRequester() }
    val accountFocus = remember { FocusRequester() }
    var settings by remember { mutableStateOf(false) }
    var confirmSignOut by remember(settings, signedIn) { mutableStateOf(false) }
    var restoreSettingsFocus by remember { mutableStateOf(false) }
    fun closeSettings() { settings = false; restoreSettingsFocus = true }
    BackHandler(enabled = settings && !confirmSignOut) { closeSettings() }
    LaunchedEffect(settings, confirmSignOut) {
        if (settings && !confirmSignOut) accountFocus.requestFocus()
        else if (restoreSettingsFocus) {
            settingsFocus.requestFocus()
            restoreSettingsFocus = false
        }
    }
    val inputMode = LocalInputModeManager.current
    val listState = rememberLazyListState()
    val browseViewports = remember { mutableStateMapOf<String, TvBrowseViewport>() }
    val rowRequesters = remember(browseKey) { mutableMapOf<String, FocusRequester>() }
    var pendingContentFocus by remember { mutableStateOf<String?>(null) }
    val listEntries = remember(entries, favorites) { tvFavoriteFirstEntries(entries, favorites) }
    fun captureViewport(focusedId: String) {
        browseViewports[browseKey] = TvBrowseViewport(
            listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, focusedId,
        )
    }
    LaunchedEffect(Unit) {
        inputMode.requestInputMode(InputMode.Keyboard)
        firstFocus.requestFocus()
    }
    LaunchedEffect(browseKey, listEpoch, listEntries.firstOrNull()?.id) {
        val saved = browseViewports[browseKey]
        if (saved != null && listEntries.isEmpty()) return@LaunchedEffect
        if (saved != null) {
            val logicalIndex = listEntries.indexOfFirst { it.id == saved.focusedId }.takeIf { it >= 0 } ?: saved.index
            listState.scrollToItem(logicalIndex.coerceAtMost((listEntries.size - 1).coerceAtLeast(0)), saved.offset)
            withFrameNanos { }
            rowRequesters[saved.focusedId]?.let { runCatching { it.requestFocus() } }
        } else if (pendingContentFocus == browseKey.substringBefore('|') && listEntries.isNotEmpty()) {
            listState.scrollToItem(0)
            withFrameNanos { }
            rowRequesters[listEntries.first().id]?.let { runCatching { it.requestFocus() } }
            pendingContentFocus = null
        } else if (listEntries.isNotEmpty()) {
            listState.scrollToItem(0)
        }
    }
    val requestMore by rememberUpdatedState(onMore)
    val entryCount by rememberUpdatedState(entries.size)
    val canLoadMore by rememberUpdatedState(hasMore && !busy && !paginationFailed && !settings)
    LaunchedEffect(listState, listEpoch) {
        snapshotFlow {
            val layout = listState.layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
            // Include the item count so an appended short page can trigger another request.
            Triple(entryCount, entryCount > 0 && last >= entryCount - 6 && last >= 0, canLoadMore)
        }.collect { (_, nearEnd, allowed) -> if (nearEnd && allowed) requestMore() }
    }
    Row(Modifier.fillMaxSize().onPreviewKeyEvent {
        val event = it.nativeKeyEvent
        val action = when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> onToggle
            KeyEvent.KEYCODE_MEDIA_PLAY -> onPlay
            KeyEvent.KEYCODE_MEDIA_PAUSE -> onPause
            else -> null
        }
        if (action != null) {
            if (event.action == KeyEvent.ACTION_UP && ready && currentId != null) action()
            true
        } else false
    }.padding(horizontal = 40.dp, vertical = 24.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Column(Modifier.width(160.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            var radioFocused by remember { mutableStateOf(false) }
            IconButton(onClick = { settings = false; onSource("radio") },
                modifier = Modifier.size(72.dp).focusRequester(firstFocus)
                    .onFocusChanged { radioFocused = it.isFocused }
                    .border(3.dp, if (radioFocused) MaterialTheme.colorScheme.primary else Color.Transparent,
                        RoundedCornerShape(8.dp))
                    .semantics { contentDescription = "Radio" }) {
                Image(painterResource(R.drawable.ic_radio), "Radio Guaka", Modifier.size(56.dp))
            }
            Spacer(Modifier.height(12.dp))
            listOf("plex" to "Plex", "youtube" to "YouTube", "favorites" to "Favorites", "history" to "History").forEach { (id, label) ->
                TvListRow(label, Modifier.fillMaxWidth(),
                    icon = when (id) {
                        "plex" -> R.drawable.ic_plex
                        "youtube" -> R.drawable.ic_youtube
                        "favorites" -> R.drawable.ic_star
                        "history" -> R.drawable.ic_history
                        else -> null
                    },
                    onClick = { settings = false; onSource(id) })
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (settings) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Settings", style = MaterialTheme.typography.headlineMedium)
                    AppVersionInfo()
                    Text("Plex", style = MaterialTheme.typography.titleLarge)
                    Text(if (signedIn) accountName.ifBlank { "Signed in to Plex" } else "Not signed in to Plex")
                    TvButton(if (signedIn) "Sign out of Plex" else "Sign in to Plex",
                        Modifier.focusRequester(accountFocus), onClick = {
                            if (signedIn) confirmSignOut = true else onSignIn()
                        })
                    connectionSettings()
                    TvButton("Close settings", onClick = ::closeSettings)
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (canGoBack) {
                        var backFocused by remember { mutableStateOf(false) }
                        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)
                            .onFocusChanged { backFocused = it.isFocused }
                            .border(3.dp, if (backFocused) MaterialTheme.colorScheme.primary else Color.Transparent,
                                RoundedCornerShape(8.dp))
                            .semantics { contentDescription = "Back" }) {
                            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = null)
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(title, Modifier.weight(1f).testTag("TV content heading"), style = MaterialTheme.typography.headlineMedium, maxLines = 1)
                    if (search.isBlank() && locationId.startsWith("album:")) TvFavoriteButton(locationId in favorites,
                        description = if (locationId in favorites) "Remove album $title from favorites" else "Favorite album $title",
                        onClick = { onFavorite(locationId) })
                    var settingsFocused by remember { mutableStateOf(false) }
                    IconButton(onClick = { settings = true }, modifier = Modifier.focusRequester(settingsFocus)
                        .onFocusChanged { settingsFocused = it.isFocused }
                        .border(if (settingsFocused) 3.dp else 1.dp,
                            if (settingsFocused) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(8.dp))
                        .semantics { contentDescription = "Settings" }) {
                        Icon(painterResource(R.drawable.ic_settings), contentDescription = null)
                    }
                }
                OutlinedTextField(search, onValueChange = onSearch, label = { Text("Search radio, Plex and YouTube") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                if (search.isBlank()) AlbumShuffleControls(locationId, albumShuffleActive, albumShuffleEnabled,
                    start = onShuffleAlbums, stop = onStopAlbumShuffle)
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(listEntries, key = { _, entry -> entry.id }) { _, entry ->
                        val requester = remember(entry.id) { FocusRequester() }
                        rowRequesters[entry.id] = requester
                        Row(Modifier.onFocusChanged {
                            if (it.hasFocus) {
                                captureViewport(entry.id)
                            }
                        }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TvArtwork(entry.id, entry.artwork, 48, "Artwork ${entry.id}") { loadArtwork(entry) }
                            TvListRow(entry.title, Modifier.weight(1f).focusRequester(requester), enabled = !entry.playable || ready,
                                description = if (entry.playable) "Play ${entry.title}" else "Open ${entry.title}",
                                onClick = {
                                    captureViewport(entry.id)
                                    if (!entry.playable) pendingContentFocus = entry.id
                                    onEntry(entry)
                                })
                            if (ListeningStore.isFavoriteMediaId(entry.id)) TvFavoriteButton(entry.id in favorites,
                                description = if (entry.id in favorites) "Remove ${entry.title} from favorites" else "Favorite ${entry.title}",
                                onClick = { onFavorite(entry.id) })
                        }
                    }
                    if (hasMore && paginationFailed) item { TvButton("Retry loading", enabled = !busy, onClick = onMore) }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (status.isNotBlank()) Text(status.replace("tap Retry", "select the item again"),
                    maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    currentId?.let { id ->
                        TvArtwork(id, "", 96, "Now playing artwork") { loadCurrentArtwork(id) }
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Playing on $outputName", style = MaterialTheme.typography.labelMedium)
                        Text(currentTitle.ifBlank { "Choose something to play" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleLarge)
                        NowPlayingDetails(currentArtist, currentStation, currentTitle)
                    }
                    TvButton("Speakers", enabled = currentId != null, onClick = onOutputs)
                    TvButton(if (playing) "Pause" else "Play", enabled = ready && currentId != null,
                        icon = if (playing) R.drawable.ic_pause else R.drawable.ic_play, onClick = onToggle)
                }
            }
        }
    }
    if (confirmSignOut) {
        val cancelFocus = remember { FocusRequester() }
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out of Plex?") },
            text = { Text("You’ll need to sign in again to browse and play your Plex library.") },
            dismissButton = {
                TvButton("Cancel", Modifier.focusRequester(cancelFocus), onClick = { confirmSignOut = false })
                LaunchedEffect(Unit) { cancelFocus.requestFocus() }
            },
            confirmButton = {
                TvButton("Sign out", onClick = {
                    confirmSignOut = false
                    onSignOut()
                })
            },
        )
    }
}

private data class TvBrowseViewport(val index: Int, val offset: Int, val focusedId: String)

internal fun tvFavoriteFirstEntries(entries: List<Entry>, favorites: Set<String>): List<Entry> =
    entries.sortedBy { if (it.playable && it.id in favorites) 0 else 1 }

@Composable
internal fun TvListRow(
    label: String, modifier: Modifier = Modifier, enabled: Boolean = true,
    description: String = label, icon: Int? = null, onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(modifier.heightIn(min = 56.dp).fillMaxWidth()
        .onFocusChanged { focused = it.isFocused }
        .border(if (focused) 3.dp else 1.dp, if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
            RoundedCornerShape(4.dp))
        .clickable(enabled = enabled, onClick = onClick)
        .semantics { contentDescription = description; role = Role.Button }
        .padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.CenterStart) {
        if (icon != null) Icon(painterResource(icon), contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(28.dp).testTag("TV row icon $label"))
        else Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag("TV row text $label"))
    }
}

@Composable
internal fun TvFavoriteButton(favorite: Boolean, description: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    IconButton(onClick = onClick, modifier = Modifier.onFocusChanged { focused = it.isFocused }
        .border(if (focused) 3.dp else 1.dp,
            if (focused) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(8.dp))
        .semantics { contentDescription = description }) {
        Icon(painterResource(if (favorite) R.drawable.ic_star else R.drawable.ic_star_outline), contentDescription = null)
    }
}

@Composable
internal fun TvButton(
    label: String, modifier: Modifier = Modifier, enabled: Boolean = true,
    description: String = label, icon: Int? = null, onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Button(onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp)
        .onFocusChanged { focused = it.isFocused }
        .border(3.dp, if (focused) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(12.dp))
        .semantics { contentDescription = description }, shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface)) {
        if (icon != null) Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(28.dp))
        else Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
    }
}

/** Loads only composed images; keyed effects discard obsolete loads after selection changes. */
@Composable
internal fun TvArtwork(
    id: String, artworkKey: String, size: Int, tag: String,
    load: suspend () -> ByteArray?,
) {
    var bitmap by remember(id, artworkKey) { mutableStateOf<ImageBitmap?>(null) }
    val loader by rememberUpdatedState(load)
    LaunchedEffect(id, artworkKey) {
        bitmap = withContext(Dispatchers.IO) {
            try { loader()?.let { Artwork.decode(it)?.asImageBitmap() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        }
    }
    val image = bitmap
    Box(Modifier.size(size.dp).testTag(tag), contentAlignment = Alignment.Center) {
        if (image != null) Image(image, null, Modifier.fillMaxSize().testTag("$tag image"), contentScale = ContentScale.Crop)
        else Text("♫", Modifier.testTag("$tag fallback"), style = MaterialTheme.typography.headlineMedium)
    }
}
