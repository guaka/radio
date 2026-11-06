package org.guaka.radio

/** Builds the continuation queue when a track is selected from the currently displayed album. */
internal data class AlbumTrackQueue(val ids: List<String>, val index: Int)

internal object AlbumTrackQueueBuilder {
    suspend fun resolve(
        parent: String,
        selected: Entry,
        pageSize: Int = DEFAULT_PAGE_SIZE,
        browse: suspend (String, Int, Int) -> List<Entry>,
    ): AlbumTrackQueue? {
        val parts = parent.split(':')
        if (parts.size != 3 || parts[0] != "album" ||
            !parts[1].matches(Regex("[A-Za-z0-9_-]{1,128}")) ||
            !parts[2].matches(Regex("[A-Za-z0-9_-]{1,128}")) ||
            pageSize <= 0 || pageSize > ListeningStore.MAX_QUEUE ||
            !selected.playable || selected.id.substringBeforeLast(':', "") != "track:${parts[1]}" ||
            selected.plexParentKey != parts[2]
        ) return null

        val tracks = linkedMapOf<String, Entry>()
        var page = 0
        while (tracks.size < ListeningStore.MAX_QUEUE) {
            val rows = browse(parent, page, pageSize)
            if (rows.isEmpty()) break
            var added = 0
            for (entry in rows) {
                if (entry.playable && entry.id.substringBeforeLast(':', "") == "track:${parts[1]}" &&
                    entry.plexParentKey == parts[2] && entry.id !in tracks && tracks.size < ListeningStore.MAX_QUEUE
                ) {
                    tracks[entry.id] = entry
                    added++
                }
            }
            // Some servers may repeat a page or return rows without useful tracks. Don't loop forever.
            if (added == 0 || rows.size < pageSize) break
            page++
        }
        val ids = tracks.keys.toList()
        val index = ids.indexOf(selected.id)
        return if (index < 0) null else AlbumTrackQueue(ids, index)
    }

    private const val DEFAULT_PAGE_SIZE = 50
}
