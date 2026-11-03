package org.guaka.radio

internal data class PlaybackOutputRoute(
    val speakerIds: List<String> = emptyList(),
    val mediaId: String? = null,
) {
    val isSpeakers: Boolean get() = speakerIds.isNotEmpty()
}

internal object PlaybackOutputRouting {
    fun forNewSelection(current: PlaybackOutputRoute): PlaybackOutputRoute =
        if (current.isSpeakers) current else PlaybackOutputRoute()

    fun mediaIdForPhoneReturn(current: PlaybackOutputRoute, phoneCurrentId: String?): String? =
        current.mediaId ?: phoneCurrentId

    /** Resolves and dispatches a media selection while preserving the repository's speaker source mode. */
    suspend fun selectOnSpeakers(
        mediaId: String,
        speakers: List<Speaker>,
        sourceMode: SpeakerSourceMode,
        isCurrent: () -> Boolean,
        resolveEntry: suspend (String) -> Entry,
        prepareMedia: suspend (Entry) -> SpeakerMedia,
        findTracks: suspend (Entry) -> List<SpeakerTrack>,
        startPlayback: suspend (Entry, SpeakerMedia, List<Speaker>) -> Unit,
        discardMedia: (SpeakerMedia) -> Unit,
        showTracks: (Entry, List<Speaker>, List<SpeakerTrack>) -> Unit,
    ): Boolean {
        if (!isCurrent()) return false
        val entry = resolveEntry(mediaId)
        if (!isCurrent()) return false
        if (entry.id.startsWith("radio:") || entry.id.startsWith("youtube:") || sourceMode == SpeakerSourceMode.PHONE_RELAY) {
            val media = prepareMedia(entry)
            if (!isCurrent()) {
                discardMedia(media)
                return false
            }
            startPlayback(entry, media, speakers)
        } else {
            val tracks = findTracks(entry)
            if (!isCurrent()) return false
            showTracks(entry, speakers, tracks)
        }
        return true
    }
}
