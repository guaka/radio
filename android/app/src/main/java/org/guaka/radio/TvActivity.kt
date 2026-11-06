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
    BackHandler(enabled = showSignIn || model.canGoBack) {
        if (showSignIn) { showSignIn = false; model.cancelTvSignIn() } else model.back()
    }
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
        onRefresh = { if (model.searchTerm.isBlank()) model.refresh() else model.search(); error = "" }, onFavorite = model::favorite,
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
    onRefresh: () -> Unit, onFavorite: (String) -> Unit, onSignIn: () -> Unit, onSignOut: () -> Unit,
    onToggle: () -> Unit, onPlay: () -> Unit, onPause: () -> Unit, onEntry: (Entry) -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    val inputMode = LocalInputModeManager.current
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        inputMode.requestInputMode(InputMode.Keyboard)
        firstFocus.requestFocus()
    }
    LaunchedEffect(listEpoch) { listState.scrollToItem(0) }
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
            Image(painterResource(R.drawable.ic_radio), null, Modifier.size(56.dp))
            Text("Radio Guaka", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            listOf("radio" to "Radio", "favorites" to "Favorites", "history" to "History", "plex" to "Plex", "youtube" to "YouTube").forEachIndexed { i, (id, label) ->
                TvButton(label, Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(firstFocus) else Modifier),
                    onClick = { onSource(id) })
            }
            if (canGoBack) TvButton("Back", onClick = onBack)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f), maxLines = 1)
                TvButton(if (signedIn) "Sign out of Plex" else "Sign in to Plex",
                    onClick = if (signedIn) onSignOut else onSignIn)
            }
            OutlinedTextField(search, onValueChange = onSearch, label = { Text("Search radio, Plex and YouTube") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(entries, key = { it.id }) { entry ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TvButton(entry.title, Modifier.weight(1f), enabled = !entry.playable || ready,
                            description = if (entry.playable) "Play ${entry.title}" else "Open ${entry.title}",
                            onClick = { onEntry(entry) })
                        if (entry.playable) TvButton(if (entry.id in favorites) "★" else "☆",
                            description = if (entry.id in favorites) "Remove ${entry.title} from favorites" else "Favorite ${entry.title}",
                            onClick = { onFavorite(entry.id) })
                    }
                }
                if (hasMore) item { TvButton("Load more", enabled = !busy, onClick = onMore) }
                item { TvButton("Refresh", enabled = !busy, onClick = onRefresh) }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (status.isNotBlank()) Text(status.replace("tap Retry", "select the item again"),
                maxLines = 2, style = MaterialTheme.typography.bodyMedium)
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Playing on this TV", style = MaterialTheme.typography.labelMedium)
                    Text(currentTitle.ifBlank { "Choose something to play" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleLarge)
                }
                TvButton(if (playing) "Pause" else "Play", enabled = ready && currentId != null, onClick = onToggle)
            }
        }
    }
}

@Composable
private fun TvButton(
    label: String, modifier: Modifier = Modifier, enabled: Boolean = true,
    description: String = label, onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Button(onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp)
        .onFocusChanged { focused = it.isFocused }
        .border(3.dp, if (focused) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(12.dp))
        .semantics { contentDescription = description }, shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface)) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
    }
}
