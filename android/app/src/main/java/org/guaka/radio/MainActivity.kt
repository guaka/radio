@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package org.guaka.radio

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    var status by mutableStateOf(""); private set
    var busy by mutableStateOf(false); private set
    var loginUrl by mutableStateOf<String?>(null); private set
    var speakers by mutableStateOf<List<Speaker>?>(null); private set
    var speakerTracks by mutableStateOf<List<SpeakerTrack>?>(null); private set
    var selectedSpeaker by mutableStateOf<Speaker?>(null); private set
    var selectedEntry: Entry? = null
    var output by mutableStateOf("This phone"); private set
    private var browseJob: Job? = null
    private var loginJob: Job? = null
    private val history = mutableListOf<String>()
    init { browse("radio") }
    private suspend fun <T> io(block: suspend () -> T) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { block() }
    fun action(block: suspend () -> Unit) = viewModelScope.launch {
        busy = true
        try { block() } catch (e: CancellationException) { throw e }
        catch (e: Exception) { status = when (e) {
            is java.net.UnknownHostException, is java.net.ConnectException, is java.net.SocketTimeoutException -> "Cannot connect. Check your network and server address."
            is javax.net.ssl.SSLException -> "Secure connection failed. Check the server certificate and address."
            else -> e.message?.takeIf { !it.contains("http") } ?: "Request failed. Check the connection and try again."
        } } finally { busy = false }
    }
    fun browse(id: String, remember: Boolean = true) {
        if (remember && id != parent) history.add(parent)
        parent = id
        entries = emptyList()
        browseJob?.cancel()
        browseJob = action { entries = repository.browse(id); status = if (entries.isEmpty()) "No items found." else "" }
    }
    fun back() { if (history.isNotEmpty()) browse(history.removeAt(history.lastIndex), false) }
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
                    loginUrl = null
                    browse("plex")
                    return@action
                }
            }
            status = "Plex sign-in expired. Tap Sign in to try again."
        }
    }
    fun signOut() {
        loginJob?.cancel(); loginUrl = null
        repository.savePlexToken("")
        entries = emptyList(); status = "Signed out of Plex."
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
        else action {
            speakerTracks = repository.speakerTracks(entry)
            status = "Confirm the matching track from Music Assistant."
        }
    }
    fun playOnSpeaker(uri: String, pausePhone: () -> Unit) = action {
        val speaker = selectedSpeaker ?: return@action
        repository.playSpeaker(speaker, uri)
        pausePhone()
        output = speaker.name
        speakerTracks = null
        status = "Started on ${speaker.name}. Music Assistant owns speaker playback."
    }
    fun phoneOutput() { output = "This phone" }
    fun dismissSpeakers() { speakers = null; speakerTracks = null }
    fun saveAssistant(url: String, token: String) = action {
        io { repository.saveAssistant(url, token) }
        status = "Music Assistant saved. Connect to home Wi-Fi to choose speakers."
    }
}

@Composable
fun AppScreen(model: MainModel = viewModel()) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var browser by remember { mutableStateOf<MediaBrowser?>(null) }
    var current by remember { mutableStateOf<MediaItem?>(null) }
    var playing by remember { mutableStateOf(false) }
    var playerError by remember { mutableStateOf("") }
    var settings by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val future = MediaBrowser.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java)))
            .setListener(object : MediaBrowser.Listener {
                override fun onError(controller: androidx.media3.session.MediaController, sessionError: androidx.media3.session.SessionError) {
                    playerError = "Playback request failed. Check your connection and Plex sign-in, then try again."
                }
            }).buildAsync()
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                current = player.currentMediaItem; playing = player.isPlaying
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                playerError = "Playback failed. Check your connection, then try the track or station again."
            }
        }
        future.addListener({ runCatching { future.get() }.onSuccess {
            browser = it; it.addListener(listener); current = it.currentMediaItem; playing = it.isPlaying
        }.onFailure { playerError = "Could not connect to the playback service. Reopen the app." } }, context.mainExecutor)
        onDispose { browser?.removeListener(listener); MediaBrowser.releaseFuture(future) }
    }
    LaunchedEffect(model.loginUrl) {
        model.loginUrl?.let { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
    }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Radio Guaka", style = MaterialTheme.typography.headlineMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { model.browse("radio") }) { Text("Radio") }
            Button(onClick = { model.browse("plex") }) { Text("Plex") }
            TextButton(onClick = { settings = true }) { Text("Setup") }
        }
        Row {
            TextButton(onClick = model::back) { Text("Back") }
            TextButton(onClick = { model.browse(model.parent, false) }) { Text("Refresh") }
            if (model.parent == "plex") TextButton(onClick = model::signIn) { Text("Sign in to Plex") }
        }
        if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (model.status.isNotBlank()) Text(model.status, style = MaterialTheme.typography.bodyMedium)
        if (playerError.isNotBlank()) Text(playerError, color = MaterialTheme.colorScheme.error)
        LazyColumn(Modifier.weight(1f)) {
            items(model.entries, key = { it.id }) { entry ->
                ListItem(headlineContent = { Text(entry.title) }, supportingContent = { Text(entry.subtitle) },
                    modifier = Modifier.clickable(enabled = !entry.playable || browser != null) {
                        if (entry.playable) {
                            playerError = ""; model.phoneOutput()
                            browser?.setMediaItem(entry.asMediaItem()); browser?.prepare(); browser?.play()
                        } else model.browse(entry.id)
                    })
            }
        }
        HorizontalDivider()
        Text("Output: ${model.output}")
        Text(current?.mediaMetadata?.title?.toString() ?: "Choose a station or track")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = current != null && model.output == "This phone", onClick = {
                if (playing) browser?.pause() else { browser?.prepare(); browser?.play() }
            }) { Text(if (playing) "Pause" else "Play") }
            OutlinedButton(onClick = { model.speakerPicker(current) }, enabled = current != null) { Text("Speakers") }
        }
    }
    if (settings) {
        var url by remember { mutableStateOf(model.repository.assistantUrl()) }
        var token by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { settings = false }, title = { Text("Connections") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Plex works away from home when your server's Remote Access is configured.")
                TextButton(onClick = { model.signIn(); settings = false }) { Text("Sign in to Plex") }
                TextButton(onClick = { browser?.stop(); model.signOut() }) { Text("Sign out of Plex") }
                Text("Music Assistant · home Wi-Fi only")
                OutlinedTextField(url, { url = it }, label = { Text("Local server URL") }, placeholder = { Text("http://192.168.1.20:8095") }, singleLine = true)
                OutlinedTextField(token, { token = it }, label = { Text("Access token") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                Text("Create an access token in Music Assistant → Settings → Profile.")
            }
        }, confirmButton = { TextButton(onClick = { model.saveAssistant(url, token); settings = false }) { Text("Save Music Assistant") } },
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
