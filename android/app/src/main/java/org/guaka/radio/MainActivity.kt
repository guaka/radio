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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    internal val sleepTimer = (application as RadioApp).sleepTimer
    internal val bookstr = BookstrController(application)
    internal val nostr = NostrFavoritesController(repository, viewModelScope)
    internal var nostrLaunchIntent: ((Intent) -> Unit)? = null
    internal val nostrBridge = AndroidSignerBridge({ intent ->
        requireNotNull(nostrLaunchIntent) { "Open the app to approve signing." }(intent)
    })
    var entries by mutableStateOf<List<Entry>>(emptyList()); private set
    var parent by mutableStateOf("radio"); private set
    var locationTitle by mutableStateOf("Radio"); private set
    var searchTerm by mutableStateOf("")
    var searchResults by mutableStateOf<Map<String, List<Entry>>>(emptyMap()); private set
    var searchErrors by mutableStateOf<Map<String, String>>(emptyMap()); private set
    var searchingSources by mutableStateOf<Set<String>>(emptySet()); private set
    var hasMore by mutableStateOf(false); private set
    var paginationFailed by mutableStateOf(false); private set
    var listEpoch by mutableIntStateOf(0); private set
    var favorites by mutableStateOf(repository.listening.favoriteIds().toSet()); private set
    var booksPlaybackSelected by mutableStateOf(false); private set
    var savedPlayback by mutableStateOf(repository.listening.loadPlayback()); private set
    var status by mutableStateOf(""); private set
    internal var statusScope by mutableStateOf(StatusScope.GENERAL); private set
    private var operations by mutableIntStateOf(0)
    val busy get() = operations > 0
    var loginUrl by mutableStateOf<String?>(null); private set
    var plexSignedIn by mutableStateOf(repository.credentials.get("plexToken").isNotBlank()); private set
    var plexAccountName by mutableStateOf(repository.plexAccountName()); private set
    internal var speakerProgress by mutableStateOf<SpeakerProgress?>(null); private set
    private var activeSpeakers = emptyList<Speaker>()
    var activeSpeakerSelection by mutableStateOf<List<Speaker>>(emptyList()); private set
    private var speakerControlGeneration = 0L
    var plexAuthRequired by mutableStateOf(false); private set
    var speakers by mutableStateOf<List<Speaker>?>(null); private set
    var speakerTracks by mutableStateOf<List<SpeakerTrack>?>(null); private set
    private var selectedSpeakers = emptyList<Speaker>()
    private var selectedEntry: Entry? = null
    var output by mutableStateOf("This phone"); private set
    private var browseJob: Job? = null
    private var filterJob: Job? = null
    private var searchGeneration = 0
    private var loginJob: Job? = null
    private var speakerJob: Job? = null
    private var stationArtworkPreloadJob: Job? = null
    private var speakerEpoch = SpeakerRequestEpoch()
    private val history = mutableListOf<Pair<String, String>>()
    private var playedHistory = repository.listening.historyIds()
    val canGoBack get() = history.isNotEmpty()
    private var appliedQuery = ""
    private var nextPage = 0
    private var loadingMore = false
    private val pageSize = 50
    private val stopListeningObserver = repository.listening.observeChanges {
        viewModelScope.launch {
            val updated = repository.listening.favoriteIds().toSet()
            val changed = updated != favorites
            favorites = updated
            if (changed && parent == "favorites") refresh()
            val updatedHistory = repository.listening.historyIds()
            if (updatedHistory != playedHistory) {
                playedHistory = updatedHistory
                if (parent == "history") refresh()
            }
            refreshSaved()
        }
    }
    init { browse("radio"); refreshPlexIdentity() }
    private fun refreshPlexIdentity() = viewModelScope.launch {
        try { plexAccountName = io { repository.refreshPlexAccountName() } }
        catch (e: CancellationException) { throw e } catch (_: Exception) { }
    }
    suspend fun refreshSpeakerProgress() {
        val snapshot = activeSpeakers
        val generation = speakerControlGeneration
        val first = snapshot.firstOrNull() ?: return
        try {
            val updated = repository.speakerProgress(first)
            if (snapshot == activeSpeakers && generation == speakerControlGeneration) speakerProgress = updated
        } catch (e: CancellationException) { throw e } catch (_: Exception) {
            if (snapshot == activeSpeakers && generation == speakerControlGeneration) speakerProgress = null
        }
    }
    fun seekSpeakers(positionMs: Long) = action {
        val targets = activeSpeakers
        val state = speakerProgress ?: return@action
        val target = positionMs.coerceIn(0, state.durationMs)
        speakerControlGeneration++
        repository.controlSpeakers(targets, "seek", target)
        if (targets == activeSpeakers) speakerProgress = state.copy(positionMs = target, sampledAtMs = System.nanoTime() / 1_000_000)
    }
    fun toggleSpeakers() = action {
        val targets = activeSpeakers
        speakerControlGeneration++
        repository.controlSpeakers(targets, if (speakerProgress?.playing == true) "pause" else "play")
        refreshSpeakerProgress()
    }

    override fun onCleared() { bookstr.close(); nostr.close(); stopListeningObserver(); super.onCleared() }
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
        "radio" -> "Radio"; "plex" -> "Plex"; "favorites" -> "Favorites"; "history" -> "History"; "books" -> "Books"; "youtube" -> "YouTube"; else -> locationTitle
    }) {
        if (id == "radio") {
            dismissSpeakers()
            setStatus("")
        }
        if (remember && id != parent) history.add(parent to locationTitle)
        parent = id
        locationTitle = title
        filterJob?.cancel()
        searchGeneration++
        searchTerm = ""
        appliedQuery = ""
        if (id == "books") refreshBooks()
        load(reset = true)
    }
    fun refreshBooks() {
        if (bookstr.state.connected) bookstr.refreshCatalog() else bookstr.connect()
    }
    fun refresh() = load(reset = true)
    fun updateFilter(value: String) {
        searchTerm = value
        filterJob?.cancel()
        val generation = ++searchGeneration
        listEpoch++
        searchResults = emptyMap()
        searchErrors = emptyMap()
        searchingSources = emptySet()
        setStatus("")
        if (value.isNotBlank()) filterJob = viewModelScope.launch {
            delay(500)
            searchAll(value.trim(), generation)
        }
    }
    fun search() {
        filterJob?.cancel()
        val generation = ++searchGeneration
        val query = searchTerm.trim()
        if (query.isNotEmpty()) filterJob = viewModelScope.launch { searchAll(query, generation) }
    }
    private suspend fun searchAll(query: String, generation: Int) = kotlinx.coroutines.coroutineScope {
        val sources = listOf("radio", "plex", "youtube")
        searchResults = emptyMap()
        searchErrors = emptyMap()
        searchingSources = sources.toSet()
        sources.forEach { source ->
            launch {
                try {
                    if (source == "plex" && repository.credentials.get("plexToken").isBlank()) {
                        searchErrors = searchErrors + (source to "Sign in to search Plex.")
                        return@launch
                    }
                    val results = repository.search(query, source, 0, pageSize)
                    if (generation == searchGeneration) searchResults = searchResults + (source to results)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (generation == searchGeneration) searchErrors = searchErrors + (source to if (source == "plex") {
                        "Plex search unavailable. Check your connection and sign-in."
                    } else {
                        "Search unavailable. Check your connection and try again."
                    })
                } finally {
                    if (generation == searchGeneration) searchingSources = searchingSources - source
                }
            }
        }
    }
    fun loadMore() {
        if (searchTerm.isBlank() && hasMore && !busy && !loadingMore && !paginationFailed) {
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
        if (folder == "books") {
            status = ""
            return
        }
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
    fun clearHistory() = action { repository.listening.clearHistory() }
    fun favorite(mediaId: String) = action {
        val starred = io { repository.listening.toggleFavorite(mediaId) }
        nostr.favoriteChanged(mediaId, starred)
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
                    plexSignedIn = true
                    refreshPlexIdentity()
                    favorites = repository.listening.favoriteIds().toSet()
                    refreshSaved()
                    loginUrl = null
                    plexAuthRequired = false
                    setStatus("")
                    if (searchTerm.isNotBlank()) search() else browse("plex")
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
            plexSignedIn = false
            plexAccountName = ""
            val updated = repository.listening.favoriteIds().toSet()
            val changed = updated != favorites
            favorites = updated
            if (changed && parent == "favorites") refresh()
            refreshSaved()
            phoneOutput(); browse("radio")
        }
    }
    fun speakerPicker(item: MediaItem?) {
        if (item == null) { dismissSpeakers(); setStatus("Choose something to play first."); return }
        val request = beginSpeakerOperation()
        speakerJob = action {
            try {
                val entry = repository.item(item.mediaId)
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
    fun selectSpeakers(chosen: List<Speaker>, pausePhone: () -> Unit) {
        if (chosen.isEmpty()) return
        val entry = selectedEntry ?: return
        val request = beginSpeakerOperation()
        selectedSpeakers = chosen.distinctBy { it.id }
        if (entry.id.startsWith("radio:") || entry.id.startsWith("youtube:")) {
            speakerJob = action {
                val keepingLeader = activeSpeakers.firstOrNull()?.let { active ->
                    selectedSpeakers.any { it.id == active.id }
                } == true
                val uri = if (keepingLeader) "" else repository.speakerStream(entry)
                if (speakerEpoch.isCurrent(request)) startSpeakerPlayback(uri, selectedSpeakers, pausePhone, request)
            }
        } else speakerJob = action {
            val tracks = repository.speakerTracks(entry)
            speakerEpoch.publishIfCurrent(request) {
                speakerTracks = tracks
                setStatus(if (tracks.isEmpty()) "No matching tracks. Add your Plex server as a Music Assistant music provider, then sync its library."
                    else "Confirm the matching track from Music Assistant.")
            }
        }
    }
    fun playOnSpeaker(uri: String, pausePhone: () -> Unit) {
        val chosen = selectedSpeakers
        if (chosen.isEmpty()) return
        val request = beginSpeakerOperation()
        startSpeakerPlayback(uri, chosen, pausePhone, request)
    }
    private fun startSpeakerPlayback(uri: String, chosen: List<Speaker>, pausePhone: () -> Unit, request: Long) {
        speakerJob = action {
            setStatus(if (chosen.size > 1) "Synchronizing speakers…" else "Sending to ${chosen.first().name}…")
            val previousTarget = activeSpeakers.firstOrNull()
            val target = repository.synchronizedSpeaker(chosen, activeSpeakerSelection)
            if (!speakerEpoch.isCurrent(request)) return@action
            val sameTarget = previousTarget?.id == target.id
            if (!sameTarget) repository.playSpeaker(target, uri)
            val previousPauseFailed = if (previousTarget != null && !sameTarget) {
                try {
                    repository.controlSpeakers(listOf(previousTarget), "pause")
                    false
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    true
                }
            } else false
            speakerEpoch.publishIfCurrent(request) {
                pausePhone()
                speakerControlGeneration++
                activeSpeakers = listOf(target)
                sleepTimer.updateSpeakers(setOf(target.id))
                activeSpeakerSelection = listOf(target) + chosen.filterNot { it.id == target.id }
                if (!sameTarget) speakerProgress = null
                output = chosen.joinToString { it.name }
                speakerTracks = null
                setStatus(if (previousPauseFailed) "Playing on ${chosen.joinToString { it.name }}. Could not pause the previous speaker."
                    else "Playing on ${chosen.joinToString { it.name }}.")
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
        speakerControlGeneration++
        activeSpeakers = emptyList()
        sleepTimer.updateSpeakers(emptySet())
        activeSpeakerSelection = emptyList()
        speakerProgress = null
        output = "This phone"
    }
    fun preparePhoneOutput(onReady: () -> Unit, onFailure: () -> Unit) {
        val targets = activeSpeakers
        if (targets.isEmpty()) {
            phoneOutput()
            onReady()
            return
        }
        speakerJob?.cancel()
        speakerJob = action(onFailure = { onFailure() }) {
            repository.controlSpeakers(targets, "pause")
            if (targets == activeSpeakers) {
                phoneOutput(cancelSpeakerRequests = false)
                onReady()
            }
        }
    }
    fun openBook(mediaId: String): Boolean {
        val book = bookstr.state.books.firstOrNull { it.id == mediaId } ?: return false
        if (!book.playable) return false
        if (!bookstr.play(mediaId)) return false
        if (sleepTimer.state() != null) {
            sleepTimer.cancel()
            setStatus("Sleep timer turned off for Bookstr playback.")
        }
        booksPlaybackSelected = true
        return true
    }
    fun radioOrPlexPlaybackStarted() {
        if (booksPlaybackSelected || bookstr.state.playing) {
            booksPlaybackSelected = false
            bookstr.pause()
        }
    }
    fun bookstrPlaybackStarted() {
        booksPlaybackSelected = bookstr.state.hasActiveBook
        if (booksPlaybackSelected && sleepTimer.state() != null) {
            sleepTimer.cancel()
            setStatus("Sleep timer turned off for Bookstr playback.")
        }
    }
    fun startSleepTimer(minutes: Int) {
        sleepTimer.start(minutes, activeSpeakers.map { it.id }.toSet())
    }
    fun dismissSpeakers() {
        speakerJob?.cancel(); speakerJob = null
        speakerEpoch.next()
        speakers = null; speakerTracks = null
        selectedEntry = null; selectedSpeakers = emptyList()
    }
    fun saveAssistant(url: String, token: String, onSaved: () -> Unit) {
        dismissSpeakers()
        action {
            setStatus("Connecting to Music Assistant…")
            io { repository.saveAssistant(url, token) }
            setStatus("Connected to Music Assistant. Settings saved.")
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
    val nostrLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { model.nostrBridge.onResult(it.resultCode, it.data) }
    DisposableEffect(nostrLauncher) {
        model.nostrLaunchIntent = { nostrLauncher.launch(it) }
        model.nostr.restore(model.nostrBridge)
        onDispose { model.nostrLaunchIntent = null }
    }
    LaunchedEffect(model.nostr.authUrl) {
        model.nostr.authUrl?.let { url ->
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            model.nostr.dismissAuthUrl()
        }
    }
    var browser by remember { mutableStateOf<MediaBrowser?>(null) }
    var current by remember { mutableStateOf<MediaItem?>(null) }
    var playing by remember { mutableStateOf(false) }
    var queue by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var queueIndex by remember { mutableIntStateOf(-1) }
    var showQueue by remember { mutableStateOf(false) }
    var showSleepTimer by remember { mutableStateOf(false) }
    var sleepState by remember { mutableStateOf(model.sleepTimer.state()) }
    var sleepError by remember { mutableStateOf(model.sleepTimer.failure()) }
    var sleepNow by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    DisposableEffect(model) {
        val stop = model.sleepTimer.observe {
            sleepState = model.sleepTimer.state()
            sleepError = model.sleepTimer.failure()
        }
        onDispose { stop() }
    }
    LaunchedEffect(sleepState?.token) {
        while (sleepState != null) {
            sleepNow = android.os.SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var seekable by remember { mutableStateOf(false) }
    var playbackClockMs by remember { mutableStateOf(System.nanoTime() / 1_000_000) }
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
    var showOutputPicker by remember { mutableStateOf(false) }
    var chosenSpeakerIds by remember { mutableStateOf(setOf<String>()) }
    var assistantRemoteId by remember { mutableStateOf(model.repository.assistantRemoteId()) }
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
                Lifecycle.Event.ON_RESUME -> if (model.parent == "books") model.refreshBooks()
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
            if (player.isPlaying) {
                if (player.currentMediaItem?.mediaId?.let { ListeningStore.isStableMediaId(it) } == true)
                    model.radioOrPlexPlaybackStarted()
            }
        }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = sync(player)
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                playerFailure = PlaybackRecovery.classify(error, error.errorCode)
                playerErrorIsPlexAuth = playerFailure == PlaybackFailure.AUTHENTICATION && current?.mediaId?.startsWith("track:") == true
                playerError = if (playerFailure == PlaybackFailure.AUTHENTICATION && current?.mediaId?.startsWith("track:") != true)
                    "This stream denied access. Choose another item or retry." else PlaybackRecovery.guidance(playerFailure!!)
            }
        }
        future.addListener({ runCatching { future.get() }.onSuccess {
            browser = it; it.addListener(listener); sync(it); model.refreshSaved()
        }.onFailure { playerError = "Could not connect to the playback service. Reopen the app." } }, context.mainExecutor)
        onDispose { browser?.removeListener(listener); MediaBrowser.releaseFuture(future) }
    }
    LaunchedEffect(browser, current?.mediaId) {
        while (true) {
            playbackClockMs = System.nanoTime() / 1_000_000
            browser?.let { position = it.currentPosition.coerceAtLeast(0); duration = it.duration; seekable = it.isCurrentMediaItemSeekable }
            delay(1000)
        }
    }
    LaunchedEffect(model.output) {
        dragging = null
        if (model.output != "This phone") while (true) {
            model.refreshSpeakerProgress()
            delay(8_000)
        }
    }
    LaunchedEffect(model.loginUrl) {
        model.loginUrl?.let {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
                .onFailure { playerError = "No browser is available for Plex sign-in." }
        }
    }
    LaunchedEffect(model.bookstr.state.playing) {
        if (model.bookstr.state.playing) {
            model.bookstrPlaybackStarted()
            browser?.pause()
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
        model.preparePhoneOutput(onReady = {
        if (request != selectionEpoch) return@preparePhoneOutput
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
        }, onFailure = {
            if (request == selectionEpoch) {
                pendingSelection = false
                playerError = "Could not pause speaker playback. Try again."
            }
        })
    }
    fun retryPlayback() {
        val player = browser ?: return
        val failed = failedSelection
        val selection = if (failed != null) failed else {
            val currentId = player.currentMediaItem?.mediaId ?: return
            val ids = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.ifEmpty { listOf(currentId) }
            val index = if (player.mediaItemCount > 0) player.currentMediaItemIndex.coerceIn(ids.indices) else 0
            PlaybackSelectionRequest(ids, index,
                if (!currentId.startsWith("radio:")) player.currentPosition.coerceAtLeast(0) else 0L,
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
                Text("Build: ${BuildConfig.BUILD_TIME} (Lisbon)", style = MaterialTheme.typography.bodySmall)
                Text("Plex works away from home when your server's Remote Access is configured.")
                if (!model.plexSignedIn)
                    TextButton(onClick = { model.signIn(); closeSettings() }) { Text("Sign in to Plex") }
                if (model.plexSignedIn) {
                    Text(if (model.plexAccountName.isBlank()) "Signed in to Plex" else "Plex: ${model.plexAccountName}")
                    TextButton(onClick = model::signOut) { Text("Sign out of Plex") }
                }
                NostrConnections(model.nostr, model.nostrBridge)
                HorizontalDivider()
                Text("Music Assistant")
                OutlinedTextField(assistantRemoteId, { assistantRemoteId = it }, label = { Text("Remote ID") }, placeholder = { Text("From Music Assistant → Settings → Remote Access") }, singleLine = true)
                OutlinedTextField(assistantToken, { assistantToken = it }, label = { Text("Access token") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                if (model.repository.hasAssistantToken(assistantRemoteId)) Text("Token saved securely. Leave blank to use it, or enter a replacement.")
                Text("Enable Music Assistant → Settings → Remote Access and copy its Remote ID. Create an access token in Settings → Profile.")
                if (saveAttempted && model.status.isNotBlank()) Text(model.status, color = MaterialTheme.colorScheme.error)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = ::closeSettings) { Text("Close") }
                TextButton(enabled = !model.busy, onClick = {
                    saveAttempted = true
                    model.saveAssistant(assistantRemoteId, assistantToken) {
                        settings = false
                        saveAttempted = false
                        assistantToken = ""
                    }
                }) { Text(if (model.busy) "Checking connection…" else "Connect and save") }
            }
        } else {
        val sourceScroll = rememberScrollState()
        LaunchedEffect(model.parent) {
            sourceScroll.animateScrollTo(if (model.parent in setOf("youtube", "books")) sourceScroll.maxValue else 0)
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Icon(painterResource(R.drawable.ic_radio), contentDescription = "Radio Guaka",
                tint = Color.Unspecified, modifier = Modifier.size(24.dp))
            Row(Modifier.weight(1f).horizontalScroll(sourceScroll), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            IconToggleButton(checked = model.parent == "favorites", onCheckedChange = { model.browse("favorites") },
                modifier = Modifier.width(40.dp).background(
                    if (model.parent == "favorites") MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                    shape = androidx.compose.foundation.shape.CircleShape,
                )) { Icon(painterResource(R.drawable.ic_star), contentDescription = "Favorites") }
            FilterChip(selected = model.parent == "history", onClick = { model.browse("history") }, label = { Text("History") })
            FilterChip(selected = model.parent == "radio", onClick = { model.browse("radio") }, label = { Text("Radio") })
            FilterChip(selected = model.parent != "radio" && model.parent != "favorites" && model.parent != "history" && model.parent != "books" && model.parent != "youtube", onClick = { model.browse("plex") }, label = { Text("Plex") })
            FilterChip(selected = model.parent == "youtube", onClick = { model.browse("youtube") }, label = { Text("YouTube") })
            val booksSelected = model.parent == "books"
            IconButton(onClick = { model.browse("books") }, modifier = Modifier.width(40.dp)
                .background(
                    if (booksSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                    shape = androidx.compose.foundation.shape.CircleShape,
                )
                .semantics {
                    contentDescription = "Books"
                    selected = booksSelected
                }) {
                Icon(painterResource(R.drawable.ic_books), contentDescription = null,
                    tint = if (booksSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            }
            IconButton(onClick = ::openSettings, modifier = Modifier.width(40.dp).semantics {
                contentDescription = "Setup and connections"
                selected = false
            }) { Icon(painterResource(R.drawable.ic_settings), contentDescription = null) }
        }
        val globalSearch = model.searchTerm.isNotBlank()
        if (!globalSearch && model.parent !in setOf("radio", "plex", "favorites", "history", "books", "youtube"))
            Text(model.locationTitle, style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(model.searchTerm, model::updateFilter,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search radio, YouTube and Plex" }.onPreviewKeyEvent { event ->
                if (event.key == Key.Enter || event.key == Key.NumPadEnter) {
                    if (event.type == KeyEventType.KeyUp) submitSearch()
                    true
                } else false
            },
            placeholder = { Text("Search radio, YouTube and Plex") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submitSearch() }, onDone = { submitSearch() }),
            trailingIcon = {
                IconButton(onClick = ::submitSearch) {
                    Icon(painterResource(R.drawable.ic_search), contentDescription = "Search")
                }
            })
        if (globalSearch || model.parent !in setOf("radio", "books")) Row {
            if (globalSearch || (model.parent != "radio" && (model.parent != "favorites" ||
                    model.entries.any { it.id.startsWith("track:") } || queue.any { it.mediaId.startsWith("track:") }))) {
                TextButton(onClick = { showQueue = true }) { Text("Queue (${queue.size})") }
            }
        }
        if (!globalSearch && model.parent == "history" && model.entries.isNotEmpty())
            TextButton(onClick = model::clearHistory) { Text("Clear history") }
        if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        val showPlexAuthPrompts = model.parent !in setOf("radio", "favorites", "history", "books", "youtube")
        if (model.status.isNotBlank() && (globalSearch || !(!showPlexAuthPrompts && model.statusScope == StatusScope.PLEX)))
            Text(model.status, style = MaterialTheme.typography.bodySmall)
        val plexPlaybackAuthError = playerErrorIsPlexAuth ||
            (playerFailure == PlaybackFailure.AUTHENTICATION && model.plexAuthRequired)
        if (!globalSearch && showPlexAuthPrompts && (!model.plexSignedIn || model.plexAuthRequired || plexPlaybackAuthError))
            TextButton(onClick = model::signIn) { Text("Sign in to Plex") }
        if (model.parent != "books" && playerError.isNotBlank() && (showPlexAuthPrompts || !plexPlaybackAuthError)) {
            Text(playerError, color = MaterialTheme.colorScheme.error)
            if (playerFailure?.let { PlaybackRecovery.isRetryable(it) || it == PlaybackFailure.UNKNOWN } == true)
                TextButton(onClick = ::retryPlayback, enabled = !model.busy && browser != null) { Text("Retry playback") }
        }
        val playable = model.entries.filter { it.playable && !it.id.startsWith("radio:") }.map { it.asMediaItem() }
        if (!globalSearch && model.parent !in setOf("radio", "books") && playable.isNotEmpty()) Row {
            TextButton(onClick = { play(playable) }, enabled = browser != null) { Text("Play listed") }
            TextButton(onClick = { enqueue(playable) }, enabled = browser != null) { Text("Queue listed") }
        }
        val bookstrState = model.bookstr.state
        val visibleBooks = BooksLibrary.filter(bookstrState.books, model.searchTerm)
        val bookOwnsPlayer = BooksLibrary.ownsPlayerControls(bookstrState, model.booksPlaybackSelected)
        val activeBookId = BooksLibrary.activeBookId(bookstrState)
        if (!globalSearch && model.parent == "books" && bookstrState.error != null) {
            Text(bookstrState.error!!, color = MaterialTheme.colorScheme.error)
            if (!bookstrState.connected || bookstrState.error != null) TextButton(onClick = model::refreshBooks, enabled = !bookstrState.connecting) { Text("Retry") }
        }
        if (!globalSearch && model.parent == "books" && bookstrState.connecting) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f), state = listState) {
            if (globalSearch) {
                listOf("radio" to "Radio", "youtube" to "YouTube", "plex" to "Plex").forEach { (source, title) ->
                    item(key = "heading:$source") {
                        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
                    }
                    val results = model.searchResults[source]
                    if (source in model.searchingSources || (results == null && source !in model.searchErrors)) {
                        item(key = "loading:$source") { Text("Searching…", modifier = Modifier.padding(horizontal = 16.dp)) }
                    } else if (source in model.searchErrors) {
                        item(key = "error:$source") {
                            Text(model.searchErrors.getValue(source), modifier = Modifier.padding(horizontal = 16.dp))
                            if (source == "plex") TextButton(onClick = model::signIn) { Text("Sign in to Plex") }
                        }
                    } else if (results.isNullOrEmpty()) {
                        item(key = "empty:$source") { Text("No matches", modifier = Modifier.padding(horizontal = 16.dp)) }
                    } else {
                        items(results, key = { "$source:${it.id}" }) { entry ->
                            ListItem(
                                headlineContent = { Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                supportingContent = if (entry.id.startsWith("radio:")) null else ({ Text(entry.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) }),
                                leadingContent = { EntryArtwork(entry, model) },
                                trailingContent = {
                                    if (entry.playable) Row {
                                        FavoriteButton(entry.id, entry.title, model)
                                        if (!entry.id.startsWith("radio:")) IconButton(onClick = { enqueue(listOf(entry.asMediaItem())) }, enabled = browser != null,
                                            modifier = Modifier.semantics { contentDescription = "Queue ${entry.title}" }) { Text("+Q") }
                                    }
                                },
                                modifier = Modifier.clickable(enabled = !entry.playable || browser != null) {
                                    if (entry.playable) play(listOf(entry.asMediaItem())) else model.browse(entry.id, title = entry.title)
                                },
                            )
                        }
                    }
                }
            } else if (model.parent == "books") {
                items(visibleBooks, key = { it.id }) { book ->
                    ListItem(
                        headlineContent = { Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(if (book.author.isBlank()) book.format.uppercase() else "${book.author} · ${book.format.uppercase()}",
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        leadingContent = { Icon(painterResource(R.drawable.ic_books), contentDescription = null,
                            modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        trailingContent = {
                            if (book.playable) IconButton(onClick = {
                                model.openBook(book.id)
                            }, modifier = Modifier.semantics { contentDescription = "Open ${book.title} in Bookstr" }) {
                                Icon(painterResource(R.drawable.ic_play), contentDescription = null)
                            } else Text("Unavailable", style = MaterialTheme.typography.labelSmall)
                        },
                        modifier = Modifier.clickable(enabled = book.playable) {
                            model.openBook(book.id)
                        },
                    )
                }
                if (bookstrState.connected && visibleBooks.isEmpty()) item {
                    Text(if (bookstrState.books.isEmpty()) "No downloaded books in Bookstr." else "No books match this search.",
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
                }
            } else {
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
            }
            if (!globalSearch && model.hasMore && model.paginationFailed) item {
                TextButton(onClick = model::retryLoadMore, enabled = !model.busy) { Text("Could not load more. Retry") }
            }
        }
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { showSleepTimer = true }, enabled = !bookOwnsPlayer) {
                val remaining = sleepState?.let { ((it.deadline - sleepNow).coerceAtLeast(0) + 59_999) / 60_000 }
                Text(if (remaining == null) "Sleep timer" else if (remaining == 0L) "Sleep soon" else "Sleep: ${remaining}m")
            }
            if (sleepError != null) Text(sleepError!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (bookOwnsPlayer) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                val title = bookstrState.currentTitle
                    ?: bookstrState.books.firstOrNull { it.id == activeBookId }?.title
                    ?: "Bookstr"
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("Bookstr", style = MaterialTheme.typography.labelSmall)
            }
        } else current?.let { item ->
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                val title = item.mediaMetadata.title?.toString().orEmpty()
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                FavoriteButton(item.mediaId, title, model)
            }
        }
        val remoteOutput = model.output != "This phone"
        val progress = model.speakerProgress
        val sliderDuration = if (remoteOutput) progress?.durationMs ?: 0L else duration
        val sliderPosition = if (remoteOutput) progress?.positionAt(playbackClockMs) ?: 0L else position
        if (!bookOwnsPlayer && sliderDuration > 0 && (remoteOutput || seekable)) {
            Slider(value = dragging ?: sliderPosition.toFloat().coerceIn(0f, sliderDuration.toFloat()),
                onValueChange = { dragging = it }, valueRange = 0f..sliderDuration.toFloat(),
                enabled = !model.busy && !pendingSelection,
                onValueChangeFinished = {
                    dragging?.toLong()?.let { target ->
                        if (remoteOutput) model.seekSpeakers(target) else { browser?.seekTo(target); position = target }
                    }
                    dragging = null
                }, modifier = Modifier.semantics { contentDescription = "Playback position" })
            Text("${durationLabel(dragging?.toLong() ?: sliderPosition)} / ${durationLabel(sliderDuration)}", style = MaterialTheme.typography.labelSmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            FilledIconButton(enabled = if (bookOwnsPlayer) bookstrState.connected && activeBookId != null
                else current != null && (!remoteOutput || progress != null) && !model.busy, onClick = {
                if (bookOwnsPlayer) model.bookstr.togglePlayPause()
                else if (remoteOutput) model.toggleSpeakers()
                else if (playing) browser?.pause() else { browser?.prepare(); browser?.play() }
            }) {
                val isPlaying = if (bookOwnsPlayer) bookstrState.playing else if (remoteOutput) progress?.playing == true else playing
                Icon(painterResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                    contentDescription = if (isPlaying) "Pause" else "Play")
            }
            if (bookOwnsPlayer && activeBookId != null) {
                IconButton(onClick = model.bookstr::skipPrevious, modifier = Modifier.semantics { contentDescription = "Previous book page" }) { Text("‹") }
                IconButton(onClick = model.bookstr::skipNext, modifier = Modifier.semantics { contentDescription = "Next book page" }) { Text("›") }
            } else if (current?.mediaId?.let { !it.startsWith("radio:") } == true) {
                IconButton(enabled = browser?.hasPreviousMediaItem() == true && model.output == "This phone",
                    onClick = { selectPlayback(queue.map { it.mediaId }, (queueIndex - 1).coerceAtLeast(0)) },
                    modifier = Modifier.semantics { contentDescription = "Previous track" }) { Text("‹") }
                IconButton(enabled = browser?.hasNextMediaItem() == true && model.output == "This phone",
                    onClick = { selectPlayback(queue.map { it.mediaId }, queueIndex + 1) },
                    modifier = Modifier.semantics { contentDescription = "Next track" }) { Text("›") }
            }
            TextButton(
                onClick = { if (!bookOwnsPlayer) { chosenSpeakerIds = model.activeSpeakerSelection.map { it.id }.toSet(); showOutputPicker = true; model.speakerPicker(current) } },
                enabled = !bookOwnsPlayer && current != null,
                modifier = Modifier.weight(1f).semantics {
                    val outputName = if (bookOwnsPlayer) routeDisplayName else if (model.output == "This phone") routeDisplayName else model.output
                    contentDescription = if (bookOwnsPlayer) "Playback output: $outputName. Android controls Bookstr audio routing."
                    else "Playback output: $outputName${if (buffering || pendingSelection) ", buffering" else ""}. Change output"
                },
            ) {
                Text(if (bookOwnsPlayer) routeDisplayName else if (model.output == "This phone") routeDisplayName else model.output,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(6.dp))
                Box(Modifier.size(18.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    if (buffering || pendingSelection || (bookOwnsPlayer && bookstrState.buffering)) CircularProgressIndicator(
                        Modifier.size(12.dp), strokeWidth = 1.5.dp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    ) else Spacer(Modifier.size(12.dp))
                }
            }
        }
        }
    }
    if (showSleepTimer) AlertDialog(
        onDismissRequest = { showSleepTimer = false },
        title = { Text("Sleep timer") },
        text = {
            Column {
                Text("Pause playback after:")
                listOf(15, 30, 45, 60).forEach { minutes ->
                    TextButton(onClick = {
                        model.startSleepTimer(minutes)
                        showSleepTimer = false
                    }) { Text("$minutes minutes") }
                }
                if (sleepState != null) TextButton(onClick = {
                    model.sleepTimer.cancel()
                    showSleepTimer = false
                }) { Text("Turn off") }
            }
        },
        confirmButton = { TextButton(onClick = { showSleepTimer = false }) { Text("Close") } },
    )
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
    if (showOutputPicker) {
        val speakers = model.speakers.orEmpty()
        fun closeOutputs() { showOutputPicker = false; model.dismissSpeakers() }
        AlertDialog(onDismissRequest = ::closeOutputs, title = { Text("Playback output") }, text = {
            Column {
                TextButton(onClick = {
                    closeOutputs()
                    val player = browser
                    val id = player?.currentMediaItem?.mediaId
                    if (player != null && id != null) {
                        val ids = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.ifEmpty { listOf(id) }
                        val index = player.currentMediaItemIndex.coerceIn(ids.indices)
                        val resumePosition = if (player.isCurrentMediaItemSeekable && !id.startsWith("radio:")) player.currentPosition.coerceAtLeast(0) else 0L
                        selectPlayback(ids, index, resumePosition)
                    } else playerError = "Choose something to play on the phone."
                }) { Text("This phone") }
                Text("Select compatible speakers to play in sync, or choose a Music Assistant group.")
                if (model.busy) Text("Loading speakers…")
                if (speakers.isEmpty() && !model.busy) Text(model.status.ifBlank { "No speakers available." })
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(speakers) { speaker ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = speaker.id in chosenSpeakerIds, onCheckedChange = { checked ->
                                chosenSpeakerIds = if (checked) chosenSpeakerIds + speaker.id else chosenSpeakerIds - speaker.id
                            })
                            Text(speaker.name)
                        }
                    }
                }
            }
        }, confirmButton = {
            TextButton(enabled = chosenSpeakerIds.isNotEmpty() && !model.busy, onClick = {
                val selected = speakers.filter { it.id in chosenSpeakerIds }
                showOutputPicker = false
                if (selected.map { it.id }.toSet() != model.activeSpeakerSelection.map { it.id }.toSet()) {
                    model.selectSpeakers(selected) { browser?.pause() }
                } else model.dismissSpeakers()
            }) { Text("Play on selected") }
        }, dismissButton = { TextButton(onClick = ::closeOutputs) { Text("Cancel") } })
    }
    model.speakerTracks?.let { tracks ->
        AlertDialog(onDismissRequest = model::dismissSpeakers, title = { Text("Confirm Music Assistant track") }, text = {
            if (tracks.isEmpty()) Text("No matching tracks. Add Plex as a music provider in Music Assistant and sync its library.")
            else LazyColumn { items(tracks) { track -> TextButton(onClick = { model.playOnSpeaker(track.uri) { browser?.pause() } }) { Text("${track.name} — ${track.artist}") } } }
        }, confirmButton = { TextButton(onClick = model::dismissSpeakers) { Text("Cancel") } })
    }
}
