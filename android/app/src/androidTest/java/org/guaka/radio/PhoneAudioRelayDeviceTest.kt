package org.guaka.radio

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PhoneAudioRelayDeviceTest {
    /** Opt-in, non-playing check: asks the configured server to inspect a harmless generated WAV. */
    @Test fun configuredAssistantCanFetchAudioFromThisPhone() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("relayServerProbe") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = (context.applicationContext as RadioApp).repository
        val token = repository.credentials.get("assistantRemoteToken")
        assumeTrue(token.isNotBlank() && repository.assistantRemoteId().isNotBlank())
        val pcmBytes = 16_000
        val wav = ByteBuffer.allocate(44 + pcmBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcmBytes); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(pcmBytes); put(ByteArray(pcmBytes))
        }.array()
        val source = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val range = request.header("Range")
            val start = range?.substringAfter("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
            val end = range?.substringAfter('-')?.toIntOrNull()?.coerceAtMost(wav.lastIndex) ?: wav.lastIndex
            val partial = range != null
            val bytes = if (request.method == "HEAD") ByteArray(0) else wav.copyOfRange(start.coerceIn(wav.indices), end + 1)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (partial) 206 else 200).message("OK")
                .header("Content-Length", if (request.method == "HEAD") wav.size.toString() else bytes.size.toString())
                .header("Accept-Ranges", "bytes")
                .header("Content-Type", "audio/wav")
                .apply { if (partial) header("Content-Range", "bytes $start-$end/${wav.size}") }
                .body(bytes.toResponseBody("audio/wav".toMediaType())).build()
        }.build()
        val activity = InstrumentationRegistry.getInstrumentation().startActivitySync(
            android.content.Intent(context, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val failures = mutableListOf<String>()
        try {
            for (address in phoneRelayAddresses()) {
                val session = repository.phoneRelay.prepare(Entry("relay-probe", "Audio relay connection check", playable = true,
                    url = "https://relay-probe.invalid/audio.wav"), source, address) { true }
                var baseline = 0
                var baselineBytes = 0L
                try {
                    // First prove the socket and fixture work, then count only server traffic.
                    val local = OkHttpClient.Builder().callTimeout(5, java.util.concurrent.TimeUnit.SECONDS).build()
                    local.newCall(okhttp3.Request.Builder().url(session.uri).build()).execute().use {
                        assertTrue("The phone must be able to fetch its own relay fixture.", it.isSuccessful && it.body.bytes().contentEquals(wav))
                    }
                    baseline = session.acceptedRequests.get()
                    baselineBytes = session.deliveredBytes.get()
                    val result = AssistantRemote(context).command(repository.assistantRemoteId(), token, "music/item_by_uri", JSONObject().put("uri", session.uri))
                    assertTrue("Server must receive audio, not just cached metadata.", session.deliveredBytes.get() > baselineBytes)
                    assertTrue("Music Assistant should recognize the relayed WAV without a separate provider.", result is JSONObject)
                    return@runBlocking
                } catch (error: Exception) {
                    val code = (error as? AssistantRemoteCommandException)?.errorCode
                    val reason = (error as? AssistantRemoteCommandException)?.details.orEmpty()
                        .replace(token, "[token]").replace(session.uri, "[relay]")
                        .replace(Regex("https?://[^\\s]+"), "[url]").take(128)
                    failures.add("${address.hostAddress}: requests=${session.acceptedRequests.get() - baseline}, bytes=${session.deliveredBytes.get() - baselineBytes}, code=$code, reason=$reason")
                } finally { repository.phoneRelay.discard(session) }
            }
            throw AssertionError("Relay probe failed: ${failures.joinToString("; ")}")
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { activity.finish() }
        }
    }
}

class PhoneAudioRelayMediaDeviceTest {
    @Test fun youtubeSourceIsFetchedThroughPhone() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("relayMediaProbe") == "true")
        verifyMedia("youtube:aqz-KE-bpKQ")
    }
    @Test fun savedPlexTrackIsFetchedThroughPhone() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("relayMediaProbe") == "true")
        val repository = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as RadioApp).repository
        val candidates = repository.listening.loadPlayback()?.ids.orEmpty() + repository.listening.historyIds() + repository.listening.favoriteIds()
        val id = candidates.firstOrNull { it.startsWith("track:") }
        assumeTrue("A previously selected Plex track is required for this opt-in check.", id != null)
        verifyMedia(id!!)
    }
    private suspend fun verifyMedia(id: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val repository = (context.applicationContext as RadioApp).repository
        assumeTrue(repository.assistantRemoteId().isNotBlank() && repository.credentials.get("assistantRemoteToken").isNotBlank())
        assertTrue("Phone relay must be the default speaker mode.", repository.speakerSourceMode() == SpeakerSourceMode.PHONE_RELAY)
        val activity = instrumentation.startActivitySync(android.content.Intent(context, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        var prepared: SpeakerMedia? = null
        try {
            prepared = repository.prepareSpeakerMedia(Entry(id, "Relay media check", playable = true))
            assertTrue("Music Assistant must fetch actual audio through the relay.", (prepared.relay?.deliveredBytes?.get() ?: 0L) > 0)
        } catch (error: Exception) {
            throw AssertionError("${if (id.startsWith("youtube:")) "YouTube" else "Plex"} relay check failed (${error.javaClass.simpleName}).")
        } finally {
            repository.phoneRelay.discard(prepared?.relay)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
