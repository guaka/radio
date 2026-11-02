package org.guaka.radio

import org.json.JSONObject

internal enum class SpeakerHandoffAction { KEEP, TRANSFER, PLAY }

/** Choose how to handle a target after grouping has completed. */
internal fun speakerHandoffAction(
    sameLeader: Boolean,
    sourceQueueAvailable: Boolean,
    explicitMediaSelection: Boolean,
): SpeakerHandoffAction = when {
    explicitMediaSelection -> SpeakerHandoffAction.PLAY
    sameLeader -> SpeakerHandoffAction.KEEP
    sourceQueueAvailable -> SpeakerHandoffAction.TRANSFER
    else -> throw IllegalStateException(
        "Cannot switch speakers because the current Music Assistant queue is unavailable. Try again."
    )
}

internal data class SpeakerQueueSnapshot(val queueId: String)

internal fun handoffResumePosition(mediaId: String, phonePositionMs: Long, remote: SpeakerProgress?, nowMs: Long): Long =
    if (mediaId.startsWith("radio:")) 0L else
        remote?.takeIf { it.durationMs > 0 }?.positionAt(nowMs) ?: phonePositionMs.coerceAtLeast(0)

internal fun parseSpeakerQueueSnapshot(queue: JSONObject?): SpeakerQueueSnapshot? =
    queue?.optString("queue_id")?.takeIf(String::isNotBlank)?.let(::SpeakerQueueSnapshot)

internal fun speakerTransferArgs(sourceQueueId: String, targetQueueId: String): JSONObject {
    require(sourceQueueId.isNotBlank()) { "The source speaker has no active queue." }
    require(targetQueueId.isNotBlank()) { "The target speaker has no active queue." }
    return JSONObject().put("source_queue_id", sourceQueueId).put("target_queue_id", targetQueueId)
}

/** Search Plex tracks by title and artist first, then relax to title for provider-specific matching. */
internal fun speakerTrackSearchQueries(entry: Entry): List<String> {
    val title = entry.title.trim()
    if (title.isEmpty()) return emptyList()
    val artist = entry.subtitle.substringBefore(" · ").trim()
    return listOfNotNull(
        "$title $artist".takeIf { artist.isNotEmpty() && !artist.equals(title, ignoreCase = true) },
        title,
    ).distinct()
}

/** Search the synced library first, then the provider catalog by the track title only. */
internal suspend fun findSpeakerTracks(
    entry: Entry,
    search: suspend (query: String, libraryOnly: Boolean) -> List<SpeakerTrack>,
): List<SpeakerTrack> {
    val queries = speakerTrackSearchQueries(entry)
    for (query in queries) {
        val matches = search(query, true)
        if (matches.isNotEmpty()) return matches
    }
    val title = queries.lastOrNull() ?: return emptyList()
    return search(title, false)
}
