@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package org.guaka.radio

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface(Modifier.fillMaxSize()) { AppScreen() } } }
    }
}

class MainModel(application: Application) : AndroidViewModel(application) {
    val repository = (application as RadioApp).repository
    var entries by mutableStateOf<List<Entry>>(emptyList()); private set
    var parent by mutableStateOf("radio"); private set
    var locationTitle by mutableStateOf("Radio"); private set
    var searchTerm by mutableStateOf("")
    var hasMore by mutableStateOf(false); private set
    var listEpoch by mutableIntStateOf(0); private set
    var favorites by mutableStateOf(repository.listening.favoriteIds().toSet()); private set
    var savedPlayback by mutableStateOf(repository.listening.loadPlayback()); private set
    var status by mutableStateOf(""); private set
    private var operations by mutableIntStateOf(0)
    val busy get() = operations > 0
    var loginUrl by mutableStateOf<String?>(null); private set
    var plexAuthRequired by mutableStateOf(false); private set
    var speakers by mutableStateOf<List<Speaker>?>(null); private set
    var speakerTracks by mutableStateOf<List<SpeakerTrack>?>(null); private set
    var selectedSpeaker by mutableStateOf<Speaker?>(null); private set
    private var selectedEntry: Entry? = null
    var output by mutableStateOf("This phone"); private set
    private var browseJob: Job? = null
    private var loginJob: Job? = null
    private val history = mutableListOf<Pair<String, String>>()
    val canGoBack get() = history.isNotEmpty()
    private var appliedQuery = ""
    private var nextPage = 0
    private val pageSize = 50
    private val stopListeningObserver = repository.listening.observeChanges {
        viewModelScope.launch {
            favorites = repository.listening.favoriteIds().toSet()
            refreshSaved()
        }
    }
    init { browse("radio") }
    override fun onCleared() { stopListeningObserver(); super.onCleared() }
    private suspend fun <T> io(block: suspend () -> T) = withContext(Dispatchers.IO) { block() }
    fun action(block: suspend () -> Unit) = viewModelScope.launch {
        operations++
        try { block() } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            plexAuthRequired = e is ApiRequestException && !e.assistant && e.statusCode in listOf(401, 403)
            status = when (e) {
            is java.net.UnknownHostException, is java.net.ConnectException, is java.net.SocketTimeoutException -> "Cannot connect. Check your network and server address."
            is javax.net.ssl.SSLException -> "Secure connection failed. Check the server certificate and address."
            else -> e.message?.takeIf { !it.contains("http") } ?: "Request failed. Check the connection and try again."
        } } finally { operations-- }
    }
    fun browse(id: String, remember: Boolean = true, title: String = when (id) {
        "radio" -> "Radio"; "plex" -> "Plex"; "favorites" -> "Favorites"; else -> locationTitle
    }) {
        if (remember && id != parent) history.add(parent to locationTitle)
        parent = id
        locationTitle = title
        searchTerm = ""
        appliedQuery = ""
        load(reset = true)
    }
    fun refresh() = load(reset = true)
    fun search() { appliedQuery = searchTerm.trim(); load(reset = true) }
    fun loadMore() { if (hasMore && !busy) load(reset = false) }
    private fun load(reset: Boolean) {
        browseJob?.cancel()
        if (reset) { entries = emptyList(); nextPage = 0; hasMore = false; listEpoch++ }
        val folder = parent
        val query = appliedQuery
        val page = nextPage
        browseJob = action {
            val result = if (query.isBlank()) repository.browse(folder, page, pageSize)
                else repository.search(query, folder, page, pageSize)
            entries = (if (reset) result else entries + result).distinctBy { it.id }
            nextPage = page + 1
            hasMore = result.size == pageSize
            status = if (entries.isEmpty()) "No items found." else ""
        }
    }
    fun back() { if (history.isNotEmpty()) { val (id, title) = history.removeAt(history.lastIndex); browse(id, false, title) } }
    fun favorite(entry: Entry) = action {
        io { repository.listening.toggleFavorite(entry.id) }
        favorites = repository.listening.favoriteIds().toSet()
        if (parent == "favorites") refresh()
    }
    fun requirePlexSignIn() { plexAuthRequired = true }
    fun refreshSaved() { savedPlayback = repository.listening.loadPlayback() }
    fun signIn() {
        loginJob?.cancel()
        loginJob = action {
            val pin = io { repository.api.createPin() }
            loginUrl = repository.api.loginUrl(pin)
            status = "Finish signing in with Plex in your browser, then return here."
            repeat(150) {
                delay(2000)
                val token = io { repository.api.pinToken(pin) }
                if (token.isNotEmpty()) {
                    io { repository.savePlexToken(token) }
                    favorites = repository.listening.favoriteIds().toSet()
                    refreshSaved()
                    loginUrl = null
                    plexAuthRequired = false
                    browse("plex")
                    return@action
                }
            }
            loginUrl = null
            status = "Plex sign-in expired. Tap Sign in to try again."
        }
    }
    fun signOut() {
        loginJob?.cancel(); browseJob?.cancel(); loginUrl = null
        action {
            io { repository.savePlexToken("") }
            favorites = repository.listening.favoriteIds().toSet()
            refreshSaved()
            dismissSpeakers(); selectedEntry = null; selectedSpeaker = null
            phoneOutput(); browse("radio")
        }
    }
    fun speakerPicker(item: MediaItem?) {
        if (item == null) { status = "Choose something to play first."; return }
        action {
            selectedEntry = repository.playable(item.mediaId)
            speakers = repository.speakers()
            status = if (speakers?.isEmpty() == true) "No available speakers. Check Music Assistant." else "Choose a speaker."
        }
    }
    fun selectSpeaker(speaker: Speaker, pausePhone: () -> Unit) {
        selectedSpeaker = speaker; speakers = null
        val entry = selectedEntry ?: return
        if (entry.id.startsWith("radio:")) playOnSpeaker(entry.url, pausePhone)
        else action { speakerTracks = repository.speakerTracks(entry); status = "Confirm the matching track from Music Assistant." }
    }
    fun playOnSpeaker(uri: String, pausePhone: () -> Unit) = action {
        val speaker = selectedSpeaker ?: return@action
        repository.playSpeaker(speaker, uri)
        pausePhone(); output = speaker.name; speakerTracks = null
        status = "Started on ${speaker.name}. Music Assistant owns speaker playback."
    }
    fun phoneOutput() { output = "This phone" }
    fun dismissSpeakers() { speakers = null; speakerTracks = null }
    fun saveAssistant(url: String, token: String, onSaved: () -> Unit) = action {
        status = ""
        io { repository.saveAssistant(url, token) }
        status = "Music Assistant saved. Connect to home Wi-Fi to choose speakers."
        onSaved()
    }
}

@Composable
private fun EntryArtwork(entry: Entry, model: MainModel) {
    var bitmap by remember(entry.id, entry.artwork) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(entry.id, entry.artwork) {
        bitmap = withContext(Dispatchers.IO) {
            val bytes = model.repository.artwork(entry) ?: return@withContext null
            Artwork.decode(bytes)?.asImageBitmap()
        }
    }
    val image = bitmap
    if (image != null) Image(image, null, Modifier.size(48.dp), contentScale = ContentScale.Crop)
    else Box(Modifier.size(48.dp)) { Text(if (entry.playable) "♫" else "▤", style = MaterialTheme.typography.headlineMedium) }
}

private fun durationLabel(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

@Composable
fun AppScreen(model: MainModel = viewModel()) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var browser by remember { mutableStateOf<MediaBrowser?>(null) }
    var current by remember { mutableStateOf<MediaItem?>(null) }
    var playing by remember { mutableStateOf(false) }
    var queue by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var queueIndex by remember { mutableIntStateOf(-1) }
    var showQueue by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var seekable by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    var playerError by remember { mutableStateOf("") }
    var playerFailure by remember { mutableStateOf<PlaybackFailure?>(null) }
    var buffering by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    LaunchedEffect(model.listEpoch) { listState.scrollToItem(0) }
    DisposableEffect(Unit) {
        val future = MediaBrowser.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java)))
            .setListener(object : MediaBrowser.Listener {
                override fun onError(controller: androidx.media3.session.MediaController, sessionError: androidx.media3.session.SessionError) {
                    playerFailure = when (sessionError.code) {
                        androidx.media3.session.SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED -> PlaybackFailure.AUTHENTICATION
                        androidx.media3.session.SessionError.ERROR_BAD_VALUE -> PlaybackFailure.NOT_FOUND
                        androidx.media3.session.SessionError.ERROR_NOT_SUPPORTED -> PlaybackFailure.UNSUPPORTED_MEDIA
                        androidx.media3.session.SessionError.ERROR_IO -> PlaybackFailure.TRANSIENT_NETWORK
                        else -> PlaybackFailure.UNKNOWN
                    }
                    if (playerFailure == PlaybackFailure.AUTHENTICATION) model.requirePlexSignIn()
                    playerError = sessionError.message
                }
            }).buildAsync()
        fun sync(player: Player) {
            current = player.currentMediaItem; playing = player.isPlaying
            buffering = player.playbackState == Player.STATE_BUFFERING
            if (player.playerError == null && player.playbackState == Player.STATE_READY) { playerError = ""; playerFailure = null }
            queue = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
            queueIndex = player.currentMediaItemIndex
            if (player.isPlaying) model.phoneOutput()
        }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = sync(player)
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                playerFailure = PlaybackRecovery.classify(error, error.errorCode)
                playerError = if (playerFailure == PlaybackFailure.AUTHENTICATION && current?.mediaId?.startsWith("radio:") == true)
                    "This station denied access. Choose another station." else PlaybackRecovery.guidance(playerFailure!!)
            }
        }
        future.addListener({ runCatching { future.get() }.onSuccess {
            browser = it; it.addListener(listener); sync(it); model.refreshSaved()
        }.onFailure { playerError = "Could not connect to the playback service. Reopen the app." } }, context.mainExecutor)
        onDispose { browser?.removeListener(listener); MediaBrowser.releaseFuture(future) }
    }
    LaunchedEffect(browser, current?.mediaId) {
        while (true) {
            browser?.let { position = it.currentPosition.coerceAtLeast(0); duration = it.duration; seekable = it.isCurrentMediaItemSeekable }
            delay(1000)
        }
    }
    LaunchedEffect(model.loginUrl) {
        model.loginUrl?.let {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
                .onFailure { playerError = "No browser is available for Plex sign-in." }
        }
    }
    BackHandler(enabled = model.canGoBack) { model.back() }
    fun enqueue(items: List<MediaItem>) {
        if (queue.size + items.size > ListeningStore.MAX_QUEUE) { playerError = "The phone queue can hold up to 500 items."; return }
        browser?.addMediaItems(items)
    }
    fun retryPlayback() {
        val player = browser ?: return
        val ids = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        val index = player.currentMediaItemIndex.coerceAtLeast(0)
        val resumePosition = if (player.currentMediaItem?.mediaId?.startsWith("track:") == true) player.currentPosition.coerceAtLeast(0) else 0L
        model.action {
            withContext(Dispatchers.IO) { model.repository.invalidatePlexConnections() }
            playerError = ""; playerFailure = null; model.phoneOutput()
            if (ids.isEmpty()) player.play()
            else {
                player.setMediaItems(ids.map { MediaItem.Builder().setMediaId(it).build() }, index, resumePosition)
                player.prepare(); player.play()
            }
        }
    }
    fun play(items: List<MediaItem>) {
        if (items.isEmpty()) return
        if (items.size > ListeningStore.MAX_QUEUE) { playerError = "Choose up to 500 items for the phone queue."; return }
        playerError = ""; playerFailure = null; model.phoneOutput()
        browser?.setMediaItems(items); browser?.prepare(); browser?.play()
    }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Radio Guaka", style = MaterialTheme.typography.headlineMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { model.browse("radio") }) { Text("Radio") }
            TextButton(onClick = { model.browse("plex") }) { Text("Plex") }
            TextButton(onClick = { model.browse("favorites") }) { Text("Favorites") }
            TextButton(onClick = { settings = true }) { Text("Setup") }
        }
        Text(model.locationTitle, style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(model.searchTerm, { model.searchTerm = it }, Modifier.weight(1f), label = { Text("Search this source") }, singleLine = true)
            TextButton(onClick = model::search, enabled = !model.busy) { Text("Search") }
        }
        Row {
            TextButton(onClick = model::back) { Text("Back") }
            TextButton(onClick = model::refresh) { Text("Refresh") }
            if (model.parent == "plex") TextButton(onClick = model::signIn) { Text("Sign in") }
            TextButton(onClick = { showQueue = true }) { Text("Queue (${queue.size})") }
        }
        if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (model.status.isNotBlank()) Text(model.status, style = MaterialTheme.typography.bodySmall)
        if (model.plexAuthRequired) TextButton(onClick = model::signIn) { Text("Sign in to Plex again") }
        if (playerError.isNotBlank()) {
            Text(playerError, color = MaterialTheme.colorScheme.error)
            if (playerFailure == PlaybackFailure.AUTHENTICATION && current?.mediaId?.startsWith("track:") == true)
                TextButton(onClick = { browser?.pause(); model.signIn() }) { Text("Sign in to Plex again") }
            else if (playerFailure?.let { PlaybackRecovery.isRetryable(it) || it == PlaybackFailure.UNKNOWN } == true)
                TextButton(onClick = ::retryPlayback, enabled = !model.busy && browser != null) { Text("Retry playback") }
        }
        if (buffering) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Buffering audio…", style = MaterialTheme.typography.bodySmall) }
        val playable = model.entries.filter { it.playable }.map { it.asMediaItem() }
        if (playable.isNotEmpty()) Row {
            TextButton(onClick = { play(playable) }, enabled = browser != null) { Text("Play listed") }
            TextButton(onClick = { enqueue(playable) }, enabled = browser != null) { Text("Queue listed") }
        }
        LazyColumn(Modifier.weight(1f), state = listState) {
            items(model.entries, key = { it.id }) { entry ->
                ListItem(headlineContent = { Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis) }, supportingContent = { Text(entry.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { EntryArtwork(entry, model) },
                    trailingContent = {
                        if (entry.playable) Row {
                            IconButton(onClick = { model.favorite(entry) }, modifier = Modifier.semantics {
                                contentDescription = if (entry.id in model.favorites) "Remove favorite ${entry.title}" else "Favorite ${entry.title}"
                            }) { Text(if (entry.id in model.favorites) "★" else "☆") }
                            IconButton(onClick = { enqueue(listOf(entry.asMediaItem())) }, enabled = browser != null,
                                modifier = Modifier.semantics { contentDescription = "Queue ${entry.title}" }) { Text("+Q") }
                        }
                    }, modifier = Modifier.clickable(enabled = !entry.playable || browser != null) {
                        if (entry.playable) play(listOf(entry.asMediaItem())) else model.browse(entry.id, title = entry.title)
                    })
            }
            if (model.hasMore) item { TextButton(onClick = model::loadMore, enabled = !model.busy) { Text("Load more") } }
        }
        HorizontalDivider()
        Text("Output: ${model.output}", style = MaterialTheme.typography.labelMedium)
        Text(current?.mediaMetadata?.title?.toString() ?: "Choose a station or track", maxLines = 1)
        if (seekable && duration > 0 && model.output == "This phone") {
            Slider(value = dragging ?: position.toFloat().coerceIn(0f, duration.toFloat()),
                onValueChange = { dragging = it }, valueRange = 0f..duration.toFloat(),
                onValueChangeFinished = { dragging?.let { browser?.seekTo(it.toLong()) }; dragging = null },
                modifier = Modifier.semantics { contentDescription = "Playback position" })
            Text("${durationLabel(position)} / ${durationLabel(duration)}", style = MaterialTheme.typography.labelSmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(enabled = current != null && model.output == "This phone", onClick = {
                if (playing) browser?.pause() else { browser?.prepare(); browser?.play() }
            }) { Text(if (playing) "Pause" else "Play") }
            IconButton(enabled = browser?.hasPreviousMediaItem() == true && model.output == "This phone",
                onClick = { browser?.seekToPreviousMediaItem() }, modifier = Modifier.semantics { contentDescription = "Previous track" }) { Text("‹") }
            IconButton(enabled = browser?.hasNextMediaItem() == true && model.output == "This phone",
                onClick = { browser?.seekToNextMediaItem() }, modifier = Modifier.semantics { contentDescription = "Next track" }) { Text("›") }
            TextButton(onClick = { model.speakerPicker(current) }, enabled = current != null) { Text("Speakers") }
        }
        if (current == null && model.savedPlayback != null) TextButton(enabled = browser != null, onClick = {
            if (model.repository.listening.loadPlayback() == null) return@TextButton
            playerError = ""; playerFailure = null; model.phoneOutput()
            // Media3 resolves the saved queue and omits stale items through the service callback.
            browser?.play()
        }) { Text("Resume saved queue") }
    }
    if (showQueue) AlertDialog(onDismissRequest = { showQueue = false }, title = { Text("Phone queue") }, text = {
        if (queue.isEmpty()) Text("Use +Q to add a station or track.")
        else LazyColumn(Modifier.heightIn(max = 420.dp)) {
            itemsIndexed(queue) { index, item ->
                Column {
                    TextButton(onClick = {
                        model.phoneOutput(); browser?.seekTo(index, C.TIME_UNSET); browser?.prepare(); browser?.play()
                    }) { Text("${if (index == queueIndex) "▶ " else ""}${item.mediaMetadata.title ?: "Track"}") }
                    Row {
                        TextButton(enabled = index > 0, onClick = { browser?.moveMediaItem(index, index - 1) }) { Text("Up") }
                        TextButton(enabled = index < queue.lastIndex, onClick = { browser?.moveMediaItem(index, index + 1) }) { Text("Down") }
                        TextButton(onClick = { browser?.removeMediaItem(index) }) { Text("Remove") }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { showQueue = false }) { Text("Done") } },
        dismissButton = { TextButton(onClick = { browser?.clearMediaItems(); model.repository.listening.savePlayback(null); model.refreshSaved() }) { Text("Clear") } })
    if (settings) {
        var url by remember { mutableStateOf(model.repository.assistantUrl()) }
        var token by remember { mutableStateOf("") }
        var saveAttempted by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { settings = false }, modifier = Modifier.imePadding(), title = { Text("Connections") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Plex works away from home when your server's Remote Access is configured.")
                TextButton(onClick = { model.signIn(); settings = false }) { Text("Sign in to Plex") }
                TextButton(onClick = model::signOut) { Text("Sign out of Plex") }
                Text("Music Assistant · home Wi-Fi only")
                OutlinedTextField(url, { url = it }, label = { Text("Local server URL") }, placeholder = { Text("http://192.168.1.20:8095") }, singleLine = true)
                OutlinedTextField(token, { token = it }, label = { Text("Access token") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                Text("Create an access token in Music Assistant → Settings → Profile.")
                if (saveAttempted && model.status.isNotBlank()) Text(model.status, color = MaterialTheme.colorScheme.error)
            }
        }, confirmButton = { TextButton(enabled = !model.busy, onClick = {
            saveAttempted = true
            model.saveAssistant(url, token) { settings = false }
        }) { Text("Save Music Assistant") } },
            dismissButton = { TextButton(onClick = { settings = false }) { Text("Close") } })
    }
    model.speakers?.let { speakers ->
        AlertDialog(onDismissRequest = model::dismissSpeakers, title = { Text("Play on a speaker") }, text = {
            LazyColumn { items(speakers) { speaker -> TextButton(onClick = { model.selectSpeaker(speaker) { browser?.pause() } }) { Text(speaker.name) } } }
        }, confirmButton = { TextButton(onClick = model::dismissSpeakers) { Text("Cancel") } })
    }
    model.speakerTracks?.let { tracks ->
        AlertDialog(onDismissRequest = model::dismissSpeakers, title = { Text("Confirm Music Assistant track") }, text = {
            if (tracks.isEmpty()) Text("No matching tracks found. Check Music Assistant's Plex library.")
            else LazyColumn { items(tracks) { track -> TextButton(onClick = { model.playOnSpeaker(track.uri) { browser?.pause() } }) { Text("${track.name} — ${track.artist}") } } }
        }, confirmButton = { TextButton(onClick = model::dismissSpeakers) { Text("Cancel") } })
    }
}
