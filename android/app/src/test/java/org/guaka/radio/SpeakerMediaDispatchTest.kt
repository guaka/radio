package org.guaka.radio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SpeakerMediaDispatchTest {
    private val kitchen = Speaker("kitchen", "Kitchen")

    @Test fun radioToYouTubePreparesAndDispatchesEachItemToTheSameSpeaker() = runBlocking {
        val prepared = mutableListOf<String>()
        val starts = mutableListOf<Pair<String, String>>()
        suspend fun dispatch(id: String) = PlaybackOutputRouting.selectOnSpeakers(
            mediaId = id,
            speakers = listOf(kitchen),
            sourceMode = SpeakerSourceMode.MUSIC_ASSISTANT,
            isCurrent = { true },
            resolveEntry = ::entry,
            prepareMedia = { item ->
                prepared += item.id
                val uri = if (item.id.startsWith("youtube:")) "builtin://track/phone-relay-youtube" else "https://radio.example/live"
                SpeakerMedia(uri)
            },
            findTracks = { error("Radio and YouTube should not search Music Assistant tracks") },
            startPlayback = { item, media, speakers ->
                assertEquals(listOf(kitchen), speakers)
                starts += item.id to media.uri
            },
            discardMedia = {},
            showTracks = { _, _, _ -> error("Radio and YouTube should not need Plex confirmation") },
        )

        assertTrue(dispatch("radio:one"))
        assertTrue(dispatch("youtube:aqz-KE-bpKQ"))
        assertEquals(listOf("radio:one", "youtube:aqz-KE-bpKQ"), prepared)
        assertEquals(
            listOf("radio:one" to "https://radio.example/live", "youtube:aqz-KE-bpKQ" to "builtin://track/phone-relay-youtube"),
            starts,
        )
    }

    @Test fun youtubeToRadioPreparesAndDispatchesEachItemToTheSameSpeaker() = runBlocking {
        val starts = mutableListOf<String>()
        suspend fun dispatch(id: String) = PlaybackOutputRouting.selectOnSpeakers(
            mediaId = id, speakers = listOf(kitchen), sourceMode = SpeakerSourceMode.MUSIC_ASSISTANT,
            isCurrent = { true }, resolveEntry = ::entry,
            prepareMedia = { SpeakerMedia("media:${it.id}") },
            findTracks = { error("Radio and YouTube should not search Music Assistant tracks") },
            startPlayback = { item, media, _ -> starts += "${item.id} -> ${media.uri}" },
            discardMedia = {},
            showTracks = { _, _, _ -> error("Radio and YouTube should not need Plex confirmation") },
        )

        assertTrue(dispatch("youtube:aqz-KE-bpKQ"))
        assertTrue(dispatch("radio:beatblender"))
        assertEquals(
            listOf("youtube:aqz-KE-bpKQ -> media:youtube:aqz-KE-bpKQ", "radio:beatblender -> media:radio:beatblender"),
            starts,
        )
    }

    @Test fun phoneRelayModePreparesPlexMediaAndStartsSpeakerPlayback() = runBlocking {
        val starts = mutableListOf<SpeakerMedia>()
        val result = PlaybackOutputRouting.selectOnSpeakers(
            mediaId = "track:server:42", speakers = listOf(kitchen), sourceMode = SpeakerSourceMode.PHONE_RELAY,
            isCurrent = { true }, resolveEntry = ::entry,
            prepareMedia = { SpeakerMedia("builtin://track/phone-relay") },
            findTracks = { error("Phone relay mode should not search the Music Assistant provider") },
            startPlayback = { _, media, _ -> starts += media },
            discardMedia = {},
            showTracks = { _, _, _ -> error("Phone relay mode should not show provider matches") },
        )

        assertTrue(result)
        assertEquals(listOf(SpeakerMedia("builtin://track/phone-relay")), starts)
    }

    @Test fun musicAssistantModeSearchesPlexProviderAndWaitsForConfirmation() = runBlocking {
        val mode = SpeakerSourceMode.MUSIC_ASSISTANT
        var prepareCalls = 0
        var startCalls = 0
        val matches = listOf(SpeakerTrack("builtin://track/provider-42", "Track", "Artist"))
        var shown: Triple<Entry, List<Speaker>, List<SpeakerTrack>>? = null
        val result = PlaybackOutputRouting.selectOnSpeakers(
            mediaId = "track:server:42", speakers = listOf(kitchen), sourceMode = mode,
            isCurrent = { true }, resolveEntry = ::entry,
            prepareMedia = { prepareCalls++; SpeakerMedia("should-not-be-used") },
            findTracks = { assertEquals("track:server:42", it.id); matches },
            startPlayback = { _, _, _ -> startCalls++ },
            discardMedia = {},
            showTracks = { item, speakers, tracks -> shown = Triple(item, speakers, tracks) },
        )

        assertTrue(result)
        assertEquals(0, prepareCalls)
        assertEquals(0, startCalls)
        assertEquals(Triple(entry("track:server:42"), listOf(kitchen), matches), shown)
    }

    @Test fun prepareFailureDoesNotStartPlaybackOrClaimTheSpeakerDestination() = runBlocking {
        var starts = 0
        var discards = 0
        val failure = runCatching {
            PlaybackOutputRouting.selectOnSpeakers(
                mediaId = "youtube:aqz-KE-bpKQ", speakers = listOf(kitchen), sourceMode = SpeakerSourceMode.MUSIC_ASSISTANT,
                isCurrent = { true }, resolveEntry = ::entry,
                prepareMedia = { throw IOException("relay could not reach Music Assistant") },
                findTracks = { error("Should prepare this source") },
                startPlayback = { _, _, _ -> starts++ },
                discardMedia = { discards++ },
                showTracks = { _, _, _ -> error("Should prepare this source") },
            )
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals(0, starts)
        assertEquals(0, discards)
    }

    @Test fun stalePreparedMediaIsDiscardedWithoutStarting() = runBlocking {
        var current = true
        var starts = 0
        val discarded = mutableListOf<SpeakerMedia>()
        val media = SpeakerMedia("builtin://track/relay")
        val result = PlaybackOutputRouting.selectOnSpeakers(
            mediaId = "youtube:aqz-KE-bpKQ", speakers = listOf(kitchen), sourceMode = SpeakerSourceMode.MUSIC_ASSISTANT,
            isCurrent = { current }, resolveEntry = ::entry,
            prepareMedia = { current = false; media },
            findTracks = { error("Should prepare this source") },
            startPlayback = { _, _, _ -> starts++ },
            discardMedia = { discarded += it },
            showTracks = { _, _, _ -> error("Should prepare this source") },
        )

        assertFalse(result)
        assertEquals(0, starts)
        assertEquals(listOf(media), discarded)
    }

    @Test fun staleRequestAfterEntryLookupDoesNotPrepareOrSearch() = runBlocking {
        var current = true
        var prepareCalls = 0
        var searchCalls = 0
        val result = PlaybackOutputRouting.selectOnSpeakers(
            mediaId = "track:server:42", speakers = listOf(kitchen),
            sourceMode = SpeakerSourceMode.PHONE_RELAY,
            isCurrent = { current },
            resolveEntry = { id -> entry(id).also { current = false } },
            prepareMedia = { prepareCalls++; SpeakerMedia("should-not-be-used") },
            findTracks = { searchCalls++; emptyList() },
            startPlayback = { _, _, _ -> error("A stale selection must not start") },
            discardMedia = {},
            showTracks = { _, _, _ -> error("A stale selection must not show confirmation") },
        )

        assertFalse(result)
        assertEquals(0, prepareCalls)
        assertEquals(0, searchCalls)
    }

    @Test fun staleMusicAssistantSearchDoesNotShowOldPlexMatches() = runBlocking {
        var current = true
        var confirmations = 0
        val result = PlaybackOutputRouting.selectOnSpeakers(
            mediaId = "track:server:42", speakers = listOf(kitchen), sourceMode = SpeakerSourceMode.MUSIC_ASSISTANT,
            isCurrent = { current }, resolveEntry = ::entry,
            prepareMedia = { error("MA provider mode should show provider matches") },
            findTracks = { current = false; listOf(SpeakerTrack("builtin://track/42", "Track", "Artist")) },
            startPlayback = { _, _, _ -> error("MA provider mode should wait for confirmation") },
            discardMedia = {},
            showTracks = { _, _, _ -> confirmations++ },
        )

        assertFalse(result)
        assertEquals(0, confirmations)
    }

    @Test fun cancellationWhilePreparingMediaDoesNotStartOrDiscardUnownedMedia() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val neverReady = CompletableDeferred<SpeakerMedia>()
        var starts = 0
        var discards = 0
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            PlaybackOutputRouting.selectOnSpeakers(
                mediaId = "youtube:aqz-KE-bpKQ", speakers = listOf(kitchen), sourceMode = SpeakerSourceMode.MUSIC_ASSISTANT,
                isCurrent = { true }, resolveEntry = ::entry,
                prepareMedia = { entered.complete(Unit); neverReady.await() },
                findTracks = { error("Should prepare this source") },
                startPlayback = { _, _, _ -> starts++ },
                discardMedia = { discards++ },
                showTracks = { _, _, _ -> error("Should prepare this source") },
            )
        }
        entered.await()
        pending.cancel()

        assertTrue(runCatching { pending.await() }.exceptionOrNull() is CancellationException)
        assertEquals(0, starts)
        assertEquals(0, discards)
    }

    @Test fun phoneReturnUsesTheCurrentRemoteMediaInsteadOfTheStalePhoneQueue() {
        assertEquals("youtube:aqz-KE-bpKQ", PlaybackOutputRouting.mediaIdForPhoneReturn(
            PlaybackOutputRoute(listOf(kitchen.id), "youtube:aqz-KE-bpKQ"), "radio:one",
        ))
        assertEquals("radio:beatblender", PlaybackOutputRouting.mediaIdForPhoneReturn(
            PlaybackOutputRoute(listOf(kitchen.id), "radio:beatblender"), "youtube:aqz-KE-bpKQ",
        ))
    }

    @Test fun phoneReturnFallsBackToPhoneCurrentMediaWhenRemoteIdentityIsUnknown() {
        assertEquals("youtube:aqz-KE-bpKQ", PlaybackOutputRouting.mediaIdForPhoneReturn(
            PlaybackOutputRoute(listOf(kitchen.id)), "youtube:aqz-KE-bpKQ",
        ))
    }

    private fun entry(id: String) = Entry(id, id, playable = true, url = "https://phone.example/$id")
}
