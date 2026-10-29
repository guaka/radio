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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
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
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.snapshotFlow

private data class PlaybackSelectionRequest(
    val ids: List<String>,
    val index: Int,
    val positionMs: Long,
    val retrySelectedOnly: Boolean,
)

internal enum class StatusScope { GENERAL, PLEX }

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
    var paginationFailed by mutableStateOf(false); private set
    var listEpoch by mutableIntStateOf(0); private set
    var favorites by mutableStateOf(repository.listening.favoriteIds().toSet()); private set
    var savedPlayback by mutableStateOf(repository.listening.loadPlayback()); private set
    var status by mutableStateOf(""); private set
    internal var statusScope by mutableStateOf(StatusScope.GENERAL); private set
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
    private var filterJob: Job? = null
    private var loginJob: Job? = null
    private var speakerJob: Job? = null
    private var stationArtworkPreloadJob: Job? = null
    private var speakerEpoch = SpeakerRequestEpoch()
    private val history = mutableListOf<Pair<String, String>>()
    val canGoBack get() = history.isNotEmpty()
    private var appliedQuery = ""
    private var nextPage = 0
    private var loadingMore = false
    private val pageSize = 50
    private val stopListeningObserver = repository.listening.observeChanges {
        viewModelScope.launch {
            favorites = repository.listening.favoriteIds().toSet()
            refreshSaved()
        }
    }
    init { browse("radio") }
    override fun onCleared() { stopListeningObserver(); super.onCleared() }
    fun startStationArtworkPreload() {
        if (stationArtworkPreloadJob?.isActive == true) return
        stationArtworkPreloadJob = viewModelScope.launch { repository.preloadStationArtwork() }
    }
    fun stopStationArtworkPreload() {
        stationArtworkPreloadJob?.cancel()
        stationArtworkPreloadJob = null
    }
    private fun setStatus(message: String, scope: StatusScope = StatusScope.GENERAL) {
        status = message
        statusScope = scope
    }
    private suspend fun <T> io(block: suspend () -> T) = withContext(Dispatchers.IO) { block() }
    internal fun action(
        onFailure: ((Exception) -> Unit)? = null,
        failureScope: StatusScope? = null,
        block: suspend () -> Unit,
    ) = viewModelScope.launch {
        operations++
        try { block() } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            plexAuthRequired = e is ApiRequestException && !e.assistant && e.statusCode in listOf(401, 403)
            val scope = failureScope ?: if (e is ApiRequestException && !e.assistant) StatusScope.PLEX else StatusScope.GENERAL
            setStatus(when (e) {
            is java.net.UnknownHostException, is java.net.ConnectException, is java.net.SocketTimeoutException -> "Cannot connect. Check your network and server address."
            is javax.net.ssl.SSLException -> "Secure connection failed. Check the server certificate and address."
            else -> e.message?.takeIf { !it.contains("http") } ?: "Request failed. Check the connection and try again."
        }, scope)
            onFailure?.invoke(e)
        } finally { operations-- }
    }
    fun browse(id: String, remember: Boolean = true, title: String = when (id) {
        "radio" -> "Radio"; "plex" -> "Plex"; "favorites" -> "Favorites"; else -> locationTitle
    }) {
        if (id == "radio") {
            dismissSpeakers()
            setStatus("")
        }
        if (remember && id != parent) history.add(parent to locationTitle)
        parent = id
        locationTitle = title
        filterJob?.cancel()
        searchTerm = ""
        appliedQuery = ""
        load(reset = true)
    }
    fun refresh() = load(reset = true)
    fun updateFilter(value: String) {
        searchTerm = value
        filterJob?.cancel()
        // Radio is local; debounce remote collections while the user types.
        if (parent == "radio" || value.isBlank()) search()
        else filterJob = viewModelScope.launch {
            delay(250)
            applyFilter()
        }
    }
    fun search() {
        filterJob?.cancel()
        applyFilter()
    }
    private fun applyFilter() {
        val query = searchTerm.trim()
        if (query == appliedQuery) return
        appliedQuery = query
        load(reset = true)
    }
    fun loadMore() {
        if (hasMore && !busy && !loadingMore && !paginationFailed) {
            loadingMore = true
            paginationFailed = false
            setStatus("")
            load(reset = false)
        }
    }
    fun retryLoadMore() {
        if (hasMore && !busy && !loadingMore && paginationFailed) {
            paginationFailed = false
            loadMore()
        }
    }
    private fun load(reset: Boolean) {
        browseJob?.cancel()
        if (reset) {
            loadingMore = false
            paginationFailed = false
            setStatus("")
            entries = emptyList(); nextPage = 0; hasMore = false; listEpoch++
        }
        val folder = parent
        val query = appliedQuery
        val page = nextPage
        browseJob = action(onFailure = { if (!reset) paginationFailed = true }) {
            try {
                val result = if (query.isBlank()) repository.browse(folder, page, pageSize)
                    else repository.search(query, folder, page, pageSize)
                val previousCount = entries.size
                val merged = (if (reset) result else entries + result).distinctBy { it.id }
                entries = merged
                nextPage = page + 1
                hasMore = result.size == pageSize && (reset || merged.size > previousCount)
                setStatus(if (entries.isEmpty()) "No items found." else "")
                paginationFailed = false
            } finally {
                loadingMore = false
            }
        }
    }
    fun back() { if (history.isNotEmpty()) { val (id, title) = history.removeAt(history.lastIndex); browse(id, false, title) } }
    fun favorite(mediaId: String) = action {
        io { repository.listening.toggleFavorite(mediaId) }
        favorites = repository.listening.favoriteIds().toSet()
        if (parent == "favorites") refresh()
    }
    fun requirePlexSignIn() { plexAuthRequired = true }
    fun refreshSaved() { savedPlayback = repository.listening.loadPlayback() }
    fun signIn() {
        loginJob?.cancel()
        loginJob = action(failureScope = StatusScope.PLEX) {
            setStatus("Starting Plex sign-in…", StatusScope.PLEX)
            val pin = io { repository.api.createPin() }
            loginUrl = repository.api.loginUrl(pin)
            setStatus("Finish signing in with Plex in your browser, then return here.", StatusScope.PLEX)
            repeat(150) {
                delay(2000)
                val token = io { repository.api.pinToken(pin) }
                if (token.isNotEmpty()) {
                    io { repository.savePlexToken(token) }
                    favorites = repository.listening.favoriteIds().toSet()
                    refreshSaved()
                    loginUrl = null
                    plexAuthRequired = false
                    setStatus("")
                    browse("plex")
                    return@action
                }
            }
            loginUrl = null
            setStatus("Plex sign-in expired. Tap Sign in to try again.", StatusScope.PLEX)
        }
    }
    fun signOut() {
        dismissSpeakers()
        loginJob?.cancel(); browseJob?.cancel(); loginUrl = null
        action {
            io { repository.savePlexToken("") }
            favorites = repository.listening.favoriteIds().toSet()
            refreshSaved()
            phoneOutput(); browse("radio")
        }
    }
    fun speakerPicker(item: MediaItem?) {
        if (item == null) { dismissSpeakers(); setStatus("Choose something to play first."); return }
        val request = beginSpeakerOperation()
        speakerJob = action {
            try {
                val entry = repository.playable(item.mediaId)
                if (!speakerEpoch.publishIfCurrent(request) { selectedEntry = entry }) return@action
                val available = repository.speakers()
                if (!speakerEpoch.publishIfCurrent(request) {
                    speakers = available
                    setStatus(if (available.isEmpty()) "No available speakers. Check Music Assistant." else "Choose a speaker.")
                }) return@action
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (speakerEpoch.isCurrent(request)) throw e
            }
        }
    }
    fun selectSpeaker(speaker: Speaker, pausePhone: () -> Unit) {
        val request = beginSpeakerOperation()
        selectedSpeaker = speaker; speakers = null; speakerTracks = null
        val entry = selectedEntry ?: return
        if (entry.id.startsWith("radio:")) startSpeakerPlayback(entry.url, speaker, pausePhone, request)
        else speakerJob = action {
            try {
                val tracks = repository.speakerTracks(entry)
                if (!speakerEpoch.publishIfCurrent(request) {
                    speakerTracks = tracks
                    setStatus("Confirm the matching track from Music Assistant.")
                }) return@action
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (speakerEpoch.isCurrent(request)) throw e
            }
        }
    }
    fun playOnSpeaker(uri: String, pausePhone: () -> Unit) {
        val speaker = selectedSpeaker ?: return
        val request = beginSpeakerOperation()
        startSpeakerPlayback(uri, speaker, pausePhone, request)
    }
    private fun startSpeakerPlayback(uri: String, speaker: Speaker, pausePhone: () -> Unit, request: Long) {
        speakerJob = action {
            try {
                repository.playSpeaker(speaker, uri)
                if (!speakerEpoch.publishIfCurrent(request) {
                    pausePhone(); output = speaker.name; speakerTracks = null
                    setStatus("Started on ${speaker.name}. Music Assistant owns speaker playback.")
                }) return@action
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (speakerEpoch.isCurrent(request)) throw e
            }
        }
    }
    private fun beginSpeakerOperation(): Long {
        speakerJob?.cancel()
        speakerJob = null
        speakers = null
        speakerTracks = null
        return speakerEpoch.next()
    }
    fun phoneOutput(cancelSpeakerRequests: Boolean = true) {
        if (cancelSpeakerRequests) dismissSpeakers()
        output = "This phone"
    }
    fun dismissSpeakers() {
        speakerJob?.cancel(); speakerJob = null
        speakerEpoch.next()
        speakers = null; speakerTracks = null
        selectedEntry = null; selectedSpeaker = null
    }
    fun saveAssistant(url: String, token: String, onSaved: () -> Unit) {
        dismissSpeakers()
        action {
            setStatus("")
            io { repository.saveAssistant(url, token) }
            setStatus("Music Assistant saved. Connect to home Wi-Fi to choose speakers.")
            onSaved()
        }
    }
}

@Composable
private fun FavoriteButton(mediaId: String, title: String, model: MainModel) {
    val favorite = mediaId in model.favorites
    IconToggleButton(checked = favorite, onCheckedChange = { model.favorite(mediaId) },
        modifier = Modifier.semantics {
            contentDescription = if (favorite) "Remove favorite $title" else "Favorite $title"
        }) {
        Icon(painterResource(if (favorite) R.drawable.ic_star else R.drawable.ic_star_outline), contentDescription = null)
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
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
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
    var playerErrorIsPlexAuth by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(false) }
    var pendingSelection by remember { mutableStateOf(false) }
    var selectionEpoch by remember { mutableIntStateOf(0) }
    var failedSelection by remember { mutableStateOf<PlaybackSelectionRequest?>(null) }
    var activeSelection by remember { mutableStateOf<PlaybackSelectionRequest?>(null) }
    var settings by remember { mutableStateOf(false) }
    var routeDisplayName by remember { mutableStateOf("This phone") }
    val routeObserver = remember(context) { AudioOutputRouteObserver(context) { routeDisplayName = it.displayName } }
    var assistantUrl by remember { mutableStateOf(model.repository.assistantUrl()) }
    var assistantToken by remember { mutableStateOf("") }
    var saveAttempted by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    fun submitSearch() {
        keyboardController?.hide()
        focusManager.clearFocus()
        model.search()
    }
    fun closeSettings() {
        settings = false
        saveAttempted = false
        assistantToken = ""
    }
    fun openSettings() {
        saveAttempted = false
        settings = true
    }
    DisposableEffect(lifecycleOwner, model, routeObserver) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    model.startStationArtworkPreload()
                    routeObserver.start()
                }
                Lifecycle.Event.ON_STOP -> {
                    model.stopStationArtworkPreload()
                    routeObserver.close()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            model.startStationArtworkPreload()
            routeObserver.start()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            model.stopStationArtworkPreload()
            routeObserver.close()
        }
    }
    fun showSessionError(error: androidx.media3.session.SessionError) {
        playerFailure = when (error.code) {
            androidx.media3.session.SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED -> PlaybackFailure.AUTHENTICATION
            androidx.media3.session.SessionError.ERROR_BAD_VALUE -> PlaybackFailure.NOT_FOUND
            androidx.media3.session.SessionError.ERROR_NOT_SUPPORTED -> PlaybackFailure.UNSUPPORTED_MEDIA
            androidx.media3.session.SessionError.ERROR_IO -> PlaybackFailure.TRANSIENT_NETWORK
            else -> PlaybackFailure.UNKNOWN
        }
        val targetId = activeSelection?.let { it.ids.getOrNull(it.index) }
            ?: failedSelection?.let { it.ids.getOrNull(it.index) }
            ?: current?.mediaId
        val isPlexAuth = playerFailure == PlaybackFailure.AUTHENTICATION &&
            (targetId?.startsWith("track:") == true || (targetId == null && current?.mediaId?.startsWith("radio:") != true))
        playerErrorIsPlexAuth = isPlexAuth
        if (isPlexAuth) model.requirePlexSignIn()
        playerError = if (playerFailure == PlaybackFailure.AUTHENTICATION && targetId?.startsWith("radio:") == true)
            "This station denied access. Choose another station." else error.message
    }
    LaunchedEffect(model.listEpoch) { listState.scrollToItem(0) }
    LaunchedEffect(listState, model.listEpoch, model.parent) {
        snapshotFlow {
            val layout = listState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
            val nearEnd = layout.totalItemsCount > 0 && lastVisible >= layout.totalItemsCount - 6
            listOf(nearEnd, model.hasMore, model.busy, model.paginationFailed, !settings)
        }.distinctUntilChanged().collect { state ->
            if (state[0] && state[1] && !state[2] && !state[3] && state[4]) model.loadMore()
        }
    }
    DisposableEffect(Unit) {
        val future = MediaBrowser.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java)))
            .setListener(object : MediaBrowser.Listener {
                override fun onError(controller: androidx.media3.session.MediaController, sessionError: androidx.media3.session.SessionError) {
                    showSessionError(sessionError)
                }
            }).buildAsync()
        fun sync(player: Player) {
            val previousMediaId = current?.mediaId
            current = player.currentMediaItem; playing = player.isPlaying
            buffering = player.playbackState == Player.STATE_BUFFERING
            if (current?.mediaId != previousMediaId) {
                failedSelection = null
                if (player.playerError == null && player.playbackState == Player.STATE_READY) {
                    playerError = ""; playerFailure = null; playerErrorIsPlexAuth = false
                }
            }
            queue = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
            queueIndex = player.currentMediaItemIndex
            if (player.isPlaying) model.phoneOutput(cancelSpeakerRequests = false)
        }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = sync(player)
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                playerFailure = PlaybackRecovery.classify(error, error.errorCode)
                playerErrorIsPlexAuth = playerFailure == PlaybackFailure.AUTHENTICATION && current?.mediaId?.startsWith("track:") == true
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
    BackHandler(enabled = settings || model.canGoBack) { if (settings) closeSettings() else model.back() }
    fun enqueue(items: List<MediaItem>) {
        if (queue.size + items.size > ListeningStore.MAX_QUEUE) { playerError = "The phone queue can hold up to 500 items."; return }
        browser?.addMediaItems(items)
    }
    fun selectPlayback(ids: List<String>, index: Int, positionMs: Long = 0L, retrySelectedOnly: Boolean = false) {
        val player = browser ?: run { playerError = "Playback service is unavailable."; return }
        if (ids.isEmpty()) return
        val request = ++selectionEpoch
        val attempted = PlaybackSelectionRequest(ids.toList(), index.coerceIn(ids.indices),
            positionMs.coerceAtLeast(0L), retrySelectedOnly)
        failedSelection = null
        activeSelection = attempted
        pendingSelection = true
        playerError = ""; playerFailure = null
        playerErrorIsPlexAuth = false
        model.phoneOutput()
        val result = player.sendCustomCommand(
            PlaybackSelectionCommand.COMMAND,
            PlaybackSelectionCommand.bundle(ids, index.coerceIn(ids.indices), positionMs.coerceAtLeast(0L), retrySelectedOnly)
        )
        result.addListener({
            if (request != selectionEpoch) return@addListener
            pendingSelection = false
            val outcome = runCatching { result.get() }.getOrNull()
            if (outcome == null || (outcome.resultCode != androidx.media3.session.SessionResult.RESULT_SUCCESS &&
                    outcome.resultCode != androidx.media3.session.SessionError.INFO_CANCELLED)) {
                failedSelection = attempted
                outcome?.sessionError?.let(::showSessionError) ?: run {
                    playerFailure = PlaybackFailure.UNKNOWN
                    playerError = "Could not start the selected item. Current playback was kept."
                }
            } else if (outcome.resultCode == androidx.media3.session.SessionResult.RESULT_SUCCESS) {
                failedSelection = null
            }
            activeSelection = null
        }, context.mainExecutor)
    }
    fun retryPlayback() {
        val player = browser ?: return
        val failed = failedSelection
        val selection = if (failed != null) failed else {
            val currentId = player.currentMediaItem?.mediaId ?: return
            val ids = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.ifEmpty { listOf(currentId) }
            val index = if (player.mediaItemCount > 0) player.currentMediaItemIndex.coerceIn(ids.indices) else 0
            PlaybackSelectionRequest(ids, index,
                if (currentId.startsWith("track:")) player.currentPosition.coerceAtLeast(0) else 0L,
                retrySelectedOnly = true)
        }
        model.action {
            withContext(Dispatchers.IO) { model.repository.invalidatePlexConnections() }
            // Retry the failed candidate when present; otherwise retry only the active queue slot.
            selectPlayback(selection.ids, selection.index, selection.positionMs, selection.retrySelectedOnly)
        }
    }
    fun play(items: List<MediaItem>) {
        if (items.isEmpty()) return
        if (items.size > ListeningStore.MAX_QUEUE) { playerError = "Choose up to 500 items for the phone queue."; return }
        selectPlayback(items.map { it.mediaId }, 0)
    }
    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding()
            .padding(horizontal = 12.dp).padding(top = 2.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (settings) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                TextButton(onClick = ::closeSettings) { Text("‹  Back") }
                Icon(painterResource(R.drawable.ic_settings), contentDescription = null, modifier = Modifier.semantics {
                    contentDescription = "Setup"
                    selected = true
                })
                Text("Connections", style = MaterialTheme.typography.titleLarge)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Plex works away from home when your server's Remote Access is configured.")
                TextButton(onClick = { model.signIn(); closeSettings() }) { Text("Sign in to Plex") }
                TextButton(onClick = model::signOut) { Text("Sign out of Plex") }
                Text("Music Assistant · home Wi-Fi only")
                OutlinedTextField(assistantUrl, { assistantUrl = it }, label = { Text("Local server URL") }, placeholder = { Text("http://192.168.1.20:8095") }, singleLine = true)
                OutlinedTextField(assistantToken, { assistantToken = it }, label = { Text("Access token") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                Text("Create an access token in Music Assistant → Settings → Profile.")
                if (saveAttempted && model.status.isNotBlank()) Text(model.status, color = MaterialTheme.colorScheme.error)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = ::closeSettings) { Text("Close") }
                TextButton(enabled = !model.busy, onClick = {
                    saveAttempted = true
                    model.saveAssistant(assistantUrl, assistantToken) {
                        settings = false
                        saveAttempted = false
                        assistantToken = ""
                    }
                }) { Text("Save Music Assistant") }
            }
        } else {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(
                painterResource(R.drawable.ic_radio),
                contentDescription = "Radio Guaka",
                tint = androidx.compose.ui.graphics.Color.Unspecified,
                modifier = Modifier.size(28.dp),
            )
            FilterChip(selected = model.parent == "favorites", onClick = { model.browse("favorites") },
                label = { Icon(painterResource(R.drawable.ic_star), contentDescription = "Favorites") },
                modifier = Modifier.semantics { contentDescription = "Favorites" })
            FilterChip(selected = model.parent == "radio", onClick = { model.browse("radio") }, label = { Text("Radio") })
            FilterChip(selected = model.parent != "radio" && model.parent != "favorites", onClick = { model.browse("plex") }, label = { Text("Plex") })
            Spacer(Modifier.weight(1f))
            IconButton(onClick = ::openSettings, modifier = Modifier.semantics {
                contentDescription = "Setup and connections"
                selected = false
            }) { Icon(painterResource(R.drawable.ic_settings), contentDescription = null) }
        }
        if (model.parent !in setOf("radio", "plex", "favorites"))
            Text(model.locationTitle, style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(model.searchTerm, model::updateFilter,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Filter items" }.onPreviewKeyEvent { event ->
                if (event.key == Key.Enter || event.key == Key.NumPadEnter) {
                    if (event.type == KeyEventType.KeyUp) submitSearch()
                    true
                } else false
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submitSearch() }, onDone = { submitSearch() }),
            trailingIcon = {
                IconButton(onClick = ::submitSearch) {
                    Icon(painterResource(R.drawable.ic_search), contentDescription = "Search")
                }
            })
        if (model.parent != "radio") Row {
            if (model.parent != "radio" && (model.parent != "favorites" ||
                    model.entries.any { it.id.startsWith("track:") } || queue.any { it.mediaId.startsWith("track:") })) {
                TextButton(onClick = { showQueue = true }) { Text("Queue (${queue.size})") }
            }
        }
        if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        val showPlexAuthPrompts = model.parent !in setOf("radio", "favorites")
        if (model.status.isNotBlank() && !(!showPlexAuthPrompts && model.statusScope == StatusScope.PLEX))
            Text(model.status, style = MaterialTheme.typography.bodySmall)
        val plexPlaybackAuthError = playerErrorIsPlexAuth ||
            (playerFailure == PlaybackFailure.AUTHENTICATION && model.plexAuthRequired)
        if (showPlexAuthPrompts && (model.parent == "plex" || model.plexAuthRequired || plexPlaybackAuthError))
            TextButton(onClick = model::signIn) { Text("Sign in to Plex") }
        if (playerError.isNotBlank() && (showPlexAuthPrompts || !plexPlaybackAuthError)) {
            Text(playerError, color = MaterialTheme.colorScheme.error)
            if (playerFailure?.let { PlaybackRecovery.isRetryable(it) || it == PlaybackFailure.UNKNOWN } == true)
                TextButton(onClick = ::retryPlayback, enabled = !model.busy && browser != null) { Text("Retry playback") }
        }
        val playable = model.entries.filter { it.playable && !it.id.startsWith("radio:") }.map { it.asMediaItem() }
        if (model.parent != "radio" && playable.isNotEmpty()) Row {
            TextButton(onClick = { play(playable) }, enabled = browser != null) { Text("Play listed") }
            TextButton(onClick = { enqueue(playable) }, enabled = browser != null) { Text("Queue listed") }
        }
        LazyColumn(Modifier.weight(1f), state = listState) {
            items(model.entries, key = { it.id }) { entry ->
                ListItem(headlineContent = { Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis) }, supportingContent = if (entry.id.startsWith("radio:")) null else ({ Text(entry.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) }),
                    leadingContent = { EntryArtwork(entry, model) },
                    trailingContent = {
                        if (entry.playable) Row {
                            FavoriteButton(entry.id, entry.title, model)
                            if (!entry.id.startsWith("radio:")) IconButton(onClick = { enqueue(listOf(entry.asMediaItem())) }, enabled = browser != null,
                                modifier = Modifier.semantics { contentDescription = "Queue ${entry.title}" }) { Text("+Q") }
                        }
                    }, modifier = Modifier.clickable(enabled = !entry.playable || browser != null) {
                        if (entry.playable) play(listOf(entry.asMediaItem())) else model.browse(entry.id, title = entry.title)
                    })
            }
            if (model.hasMore && model.paginationFailed) item {
                TextButton(onClick = model::retryLoadMore, enabled = !model.busy) { Text("Could not load more. Retry") }
            }
        }
        HorizontalDivider()
        current?.let { item ->
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                val title = item.mediaMetadata.title?.toString().orEmpty()
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                FavoriteButton(item.mediaId, title, model)
            }
        }
        if (seekable && duration > 0 && model.output == "This phone") {
            Slider(value = dragging ?: position.toFloat().coerceIn(0f, duration.toFloat()),
                onValueChange = { dragging = it }, valueRange = 0f..duration.toFloat(),
                onValueChangeFinished = { dragging?.let { browser?.seekTo(it.toLong()) }; dragging = null },
                modifier = Modifier.semantics { contentDescription = "Playback position" })
            Text("${durationLabel(position)} / ${durationLabel(duration)}", style = MaterialTheme.typography.labelSmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            FilledIconButton(enabled = current != null && model.output == "This phone", onClick = {
                if (playing) browser?.pause() else { browser?.prepare(); browser?.play() }
            }) {
                Icon(painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play),
                    contentDescription = if (playing) "Pause" else "Play")
            }
            if (model.parent != "radio" && current?.mediaId?.startsWith("track:") == true) {
                IconButton(enabled = browser?.hasPreviousMediaItem() == true && model.output == "This phone",
                    onClick = { selectPlayback(queue.map { it.mediaId }, (queueIndex - 1).coerceAtLeast(0)) },
                    modifier = Modifier.semantics { contentDescription = "Previous track" }) { Text("‹") }
                IconButton(enabled = browser?.hasNextMediaItem() == true && model.output == "This phone",
                    onClick = { selectPlayback(queue.map { it.mediaId }, queueIndex + 1) },
                    modifier = Modifier.semantics { contentDescription = "Next track" }) { Text("›") }
            }
            TextButton(
                onClick = { model.speakerPicker(current) },
                enabled = current != null,
                modifier = Modifier.weight(1f).semantics {
                    val outputName = if (model.output == "This phone") routeDisplayName else model.output
                    contentDescription = "Playback output: $outputName${if (buffering || pendingSelection) ", buffering" else ""}. Change output"
                },
            ) {
                Text(if (model.output == "This phone") routeDisplayName else model.output,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(6.dp))
                Box(Modifier.size(18.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    if (buffering || pendingSelection) CircularProgressIndicator(
                        Modifier.size(12.dp), strokeWidth = 1.5.dp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    ) else Spacer(Modifier.size(12.dp))
                }
            }
        }
        }
    }
    if (showQueue) AlertDialog(onDismissRequest = { showQueue = false }, title = { Text("Phone queue") }, text = {
        if (queue.isEmpty()) Text("Add Plex tracks to build a queue.")
        else LazyColumn(Modifier.heightIn(max = 420.dp)) {
            itemsIndexed(queue) { index, item ->
                Column {
                    TextButton(onClick = { selectPlayback(queue.map { it.mediaId }, index) }) {
                        Text("${if (index == queueIndex) "▶ " else ""}${item.mediaMetadata.title ?: "Track"}")
                    }
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
    model.speakers?.let { speakers ->
        AlertDialog(onDismissRequest = model::dismissSpeakers, title = { Text("Play on a speaker") }, text = {
            LazyColumn {
                item { TextButton(onClick = { model.phoneOutput(); browser?.prepare(); browser?.play() }) { Text("This phone") } }
                items(speakers) { speaker -> TextButton(onClick = { model.selectSpeaker(speaker) { browser?.pause() } }) { Text(speaker.name) } }
            }
        }, confirmButton = { TextButton(onClick = model::dismissSpeakers) { Text("Cancel") } })
    }
    model.speakerTracks?.let { tracks ->
        AlertDialog(onDismissRequest = model::dismissSpeakers, title = { Text("Confirm Music Assistant track") }, text = {
            if (tracks.isEmpty()) Text("No matching tracks found. Check Music Assistant's Plex library.")
            else LazyColumn { items(tracks) { track -> TextButton(onClick = { model.playOnSpeaker(track.uri) { browser?.pause() } }) { Text("${track.name} — ${track.artist}") } } }
        }, confirmButton = { TextButton(onClick = model::dismissSpeakers) { Text("Cancel") } })
    }
}
