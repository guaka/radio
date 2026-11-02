package org.guaka.radio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SpeakerHandoffTest {
    @Test fun returnToPhoneUsesSpeakerPositionInsteadOfTheOldPausedPhonePosition() {
        val remote = SpeakerProgress(120_000, 300_000, true, sampledAtMs = 10_000)
        assertEquals(122_000L, handoffResumePosition("youtube:aqz-KE-bpKQ", 5_000, remote, 12_000))
        assertEquals(0L, handoffResumePosition("radio:test", 5_000, remote, 12_000))
        assertEquals(5_000L, handoffResumePosition("track:test", 5_000, null, 12_000))
    }
    @Test fun transitionKeepsSameLeaderTransfersAnExistingQueueAndPlaysExplicitMedia() {
        assertEquals(SpeakerHandoffAction.KEEP, speakerHandoffAction(
            sameLeader = true, sourceQueueAvailable = true, explicitMediaSelection = false,
        ))
        assertEquals(SpeakerHandoffAction.TRANSFER, speakerHandoffAction(
            sameLeader = false, sourceQueueAvailable = true, explicitMediaSelection = false,
        ))
        assertEquals(SpeakerHandoffAction.PLAY, speakerHandoffAction(
            sameLeader = true, sourceQueueAvailable = true, explicitMediaSelection = true,
        ))
    }

    @Test fun changedLeaderWithoutSourceQueueFailsInsteadOfRestarting() {
        val failure = runCatching {
            speakerHandoffAction(sameLeader = false, sourceQueueAvailable = false, explicitMediaSelection = false)
        }.exceptionOrNull()

        assertEquals(IllegalStateException::class.java, failure?.javaClass)
        assertEquals("Cannot switch speakers because the current Music Assistant queue is unavailable. Try again.", failure?.message)
    }

    @Test fun transferUsesQueueIdsAndLeavesAutoPlayAtMusicAssistantDefault() {
        val args = speakerTransferArgs("source-queue", "target-queue")
        assertEquals("source-queue", args.getString("source_queue_id"))
        assertEquals("target-queue", args.getString("target_queue_id"))
        assertFalse(args.has("auto_play"))
        assertEquals(SpeakerQueueSnapshot("source-queue"),
            parseSpeakerQueueSnapshot(JSONObject("""{"queue_id":"source-queue"}""")))
        assertEquals(null, parseSpeakerQueueSnapshot(JSONObject("""{"state":"idle"}""")))
    }

    @Test fun plexSearchTriesArtistQualifiedQueryThenRelaxesToTrackTitle() {
        val entry = Entry("track:plex:42", "So What", "Miles Davis · Kind of Blue", playable = true)
        assertEquals(listOf("So What Miles Davis", "So What"), speakerTrackSearchQueries(entry))
    }

    @Test fun plexSearchAvoidsEmptyAndDuplicateQueries() {
        assertEquals(emptyList<String>(), speakerTrackSearchQueries(Entry("track:plex:42", "  ", "Miles Davis")))
        assertEquals(listOf("So What"), speakerTrackSearchQueries(Entry("track:plex:42", "So What", "So What · Album")))
        assertEquals(listOf("SO WHAT"), speakerTrackSearchQueries(Entry("track:plex:42", "SO WHAT", "so what · Album")))
    }

    @Test fun musicAssistantTrackParserKeepsUrisForExplicitPlayback() {
        val tracks = IntegrationParser.speakerTracks(JSONObject("""{
            "tracks": [
              {"uri":"plex://track/42","name":"So What","artists":[{"name":"Miles Davis"}]},
              {"name":"No URI","artists":[]}
            ]
        }"""))

        assertEquals(listOf(SpeakerTrack("plex://track/42", "So What", "Miles Davis")), tracks)
    }

    @Test fun providerCatalogFallbackUsesOnlyTheTitleAfterLibraryQueriesMiss() = runBlocking {
        val entry = Entry("track:plex:42", "So What", "Miles Davis · Kind of Blue", playable = true)
        val attempted = mutableListOf<Pair<String, Boolean>>()
        val match = SpeakerTrack("plex://track/42", "So What", "Miles Davis")

        val result = findSpeakerTracks(entry) { query, libraryOnly ->
            attempted += query to libraryOnly
            if (query == "So What" && !libraryOnly) listOf(match) else emptyList()
        }

        assertEquals(listOf("So What Miles Davis" to true, "So What" to true, "So What" to false), attempted)
        assertEquals(listOf(match), result)
    }

    @Test fun successfulLibrarySearchStopsBeforeProviderCatalogFallback() = runBlocking {
        val entry = Entry("track:plex:42", "So What", "Miles Davis · Kind of Blue", playable = true)
        val attempted = mutableListOf<Pair<String, Boolean>>()
        val match = SpeakerTrack("library://track/42", "So What", "Miles Davis")

        val result = findSpeakerTracks(entry) { query, libraryOnly ->
            attempted += query to libraryOnly
            if (query == "So What") listOf(match) else emptyList()
        }

        assertEquals(listOf("So What Miles Davis" to true, "So What" to true), attempted)
        assertEquals(listOf(match), result)
    }

    @Test fun successfulArtistQualifiedLibrarySearchStopsImmediately() = runBlocking {
        val entry = Entry("track:plex:42", "So What", "Miles Davis · Kind of Blue", playable = true)
        val attempted = mutableListOf<Pair<String, Boolean>>()
        val match = SpeakerTrack("library://track/42", "So What", "Miles Davis")

        val result = findSpeakerTracks(entry) { query, libraryOnly ->
            attempted += query to libraryOnly
            listOf(match)
        }

        assertEquals(listOf("So What Miles Davis" to true), attempted)
        assertEquals(listOf(match), result)
    }

    @Test fun cancellationStopsBeforeBroaderProviderSearch() = runBlocking {
        val entry = Entry("track:plex:42", "So What", "Miles Davis · Kind of Blue", playable = true)
        val attempted = mutableListOf<Pair<String, Boolean>>()
        val failure = runCatching {
            findSpeakerTracks(entry) { query, libraryOnly ->
                attempted += query to libraryOnly
                throw CancellationException("cancelled")
            }
        }.exceptionOrNull()

        assertEquals(CancellationException::class.java, failure?.javaClass)
        assertEquals(listOf("So What Miles Davis" to true), attempted)
    }
}
