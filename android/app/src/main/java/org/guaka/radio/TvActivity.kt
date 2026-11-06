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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
    var showSignIn by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        var attached: MediaBrowser? = null
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                current = player.currentMediaItem
                playing = player.isPlaying
                buffering = player.playbackState == Player.STATE_BUFFERING
            }
            override fun onPlayerError(failure: androidx.media3.common.PlaybackException) {
                error = PlaybackRecovery.guidance(PlaybackRecovery.classify(failure, failure.errorCode))
            }
        }
        val future = MediaBrowser.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
        var disposed = false
        future.addListener({
            if (!disposed) runCatching { future.get() }.onSuccess {
                attached = it
                browser = it
                current = it.currentMediaItem
                playing = it.isPlaying
                buffering = it.playbackState == Player.STATE_BUFFERING
                it.addListener(listener)
            }.onFailure { error = "Could not connect to playback. Reopen the app." }
        }, context.mainExecutor)
        onDispose {
            disposed = true
            attached?.removeListener(listener)
            MediaBrowser.releaseFuture(future)
        }
    }
    BackHandler(enabled = model.canGoBack) { model.back() }
    LaunchedEffect(model.plexSignedIn) { if (model.plexSignedIn) showSignIn = false }
    TvContent(
        entries = tvSearchEntries(model.searchTerm, model.entries, model.searchResults),
        title = if (model.searchTerm.isBlank()) model.locationTitle else "Search results", favorites = model.favorites,
        currentTitle = current?.mediaMetadata?.title?.toString().orEmpty(),
        currentId = current?.mediaId, playing = playing, ready = browser != null,
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
        onToggle = { browser?.let { if (it.isPlaying) it.pause() else { it.prepare(); it.play() } } },
        onPlay = { browser?.play() }, onPause = { browser?.pause() },
        onEntry = { entry ->
            if (!entry.playable) model.browse(entry.id, title = entry.title)
            else browser?.let { player ->
                val epoch = ++selectionEpoch
                selecting = true
                error = ""
                val result = player.sendCustomCommand(PlaybackSelectionCommand.COMMAND,
                    PlaybackSelectionCommand.bundle(listOf(entry.id), 0, 0L))
                result.addListener({
                    if (epoch == selectionEpoch) {
                        selecting = false
                        val outcome = runCatching { result.get() }.getOrNull()
                        if (outcome == null || (outcome.resultCode != SessionResult.RESULT_SUCCESS &&
                                outcome.resultCode != androidx.media3.session.SessionError.INFO_CANCELLED)) {
                            error = outcome?.sessionError?.message ?: "Could not play this item. Choose it again to retry."
                        }
                    }
                }, context.mainExecutor)
            }
        },
    )
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
    paginationFailed: Boolean = false, accountName: String = "",
    loadArtwork: suspend (Entry) -> ByteArray? = { null },
    loadCurrentArtwork: suspend (String) -> ByteArray? = { null },
) {
    val firstFocus = remember { FocusRequester() }
    val settingsFocus = remember { FocusRequester() }
    val accountFocus = remember { FocusRequester() }
    var settings by remember { mutableStateOf(false) }
    var restoreSettingsFocus by remember { mutableStateOf(false) }
    fun closeSettings() { settings = false; restoreSettingsFocus = true }
    BackHandler(enabled = settings) { closeSettings() }
    LaunchedEffect(settings) {
        if (settings) accountFocus.requestFocus()
        else if (restoreSettingsFocus) {
            settingsFocus.requestFocus()
            restoreSettingsFocus = false
        }
    }
    val inputMode = LocalInputModeManager.current
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        inputMode.requestInputMode(InputMode.Keyboard)
        firstFocus.requestFocus()
    }
    LaunchedEffect(listEpoch) { listState.scrollToItem(0) }
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
            Image(painterResource(R.drawable.ic_radio), "Radio Guaka", Modifier.size(56.dp))
            Spacer(Modifier.height(12.dp))
            listOf("radio" to "Radio", "favorites" to "Favorites", "history" to "History", "plex" to "Plex", "youtube" to "YouTube").forEachIndexed { i, (id, label) ->
                TvButton(label, Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(firstFocus) else Modifier),
                    onClick = { settings = false; onSource(id) })
            }
            TvButton("Settings", Modifier.fillMaxWidth().focusRequester(settingsFocus), onClick = { settings = true })
            if (canGoBack && !settings) TvButton("Back", onClick = onBack)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (settings) {
                Text("Settings", style = MaterialTheme.typography.headlineMedium)
                Text("Plex", style = MaterialTheme.typography.titleLarge)
                Text(if (signedIn) accountName.ifBlank { "Signed in to Plex" } else "Not signed in to Plex")
                TvButton(if (signedIn) "Sign out of Plex" else "Sign in to Plex",
                    Modifier.focusRequester(accountFocus), onClick = if (signedIn) onSignOut else onSignIn)
                TvButton("Close settings", onClick = ::closeSettings)
            } else {
                Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 1)
                OutlinedTextField(search, onValueChange = onSearch, label = { Text("Search radio, Plex and YouTube") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(entries, key = { it.id }) { entry ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TvArtwork(entry.id, entry.artwork, 48, "Artwork ${entry.id}") { loadArtwork(entry) }
                            TvButton(entry.title, Modifier.weight(1f), enabled = !entry.playable || ready,
                                description = if (entry.playable) "Play ${entry.title}" else "Open ${entry.title}",
                                onClick = { onEntry(entry) })
                            if (entry.playable) TvButton(if (entry.id in favorites) "★" else "☆",
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
                        Text("Playing on this TV", style = MaterialTheme.typography.labelMedium)
                        Text(currentTitle.ifBlank { "Choose something to play" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleLarge)
                    }
                    TvButton(if (playing) "Pause" else "Play", enabled = ready && currentId != null,
                        icon = if (playing) R.drawable.ic_pause else R.drawable.ic_play, onClick = onToggle)
                }
            }
        }
    }
}

@Composable
private fun TvButton(
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
