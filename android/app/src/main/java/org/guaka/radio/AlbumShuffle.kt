package org.guaka.radio

import kotlin.random.Random

/** Album order is random; the server's disc/track order within an album is preserved. */
internal object AlbumShuffle {
    fun scope(parent: String): String? = when {
        parent.startsWith("artist:") -> parent
        parent.startsWith("library:") -> parent.replaceFirst("library:", "catalog:") + ":albums"
        parent.startsWith("catalog:") -> parent.substringBeforeLast(':') + ":albums"
        else -> null
    }

    data class Selection(val albumId: String, val tracks: List<String>)

    suspend fun next(
        parent: String,
        previousAlbum: String?,
        random: Random = Random.Default,
        browse: suspend (String, Int, Int) -> List<Entry>,
    ): Selection {
        require(scope(parent) == parent) { "Choose a Plex library or artist." }
        // Reservoir sampling includes every page without retaining the entire library.
        var selected: String? = null
        var fallback: String? = null
        var count = 0
        var page = 0
        do {
            val rows = browse(parent, page++, 200)
            for (album in rows.filter { it.id.startsWith("album:") }) {
                fallback = album.id
                if (album.id != previousAlbum && random.nextInt(++count) == 0) selected = album.id
            }
        } while (rows.size == 200)
        val album = selected ?: fallback ?: error("No albums available to shuffle.")
        val tracks = mutableListOf<String>()
        page = 0
        do {
            val rows = browse(album, page++, 200)
            tracks += rows.filter { it.playable && it.id.startsWith("track:") }.map { it.id }
            require(tracks.size <= ListeningStore.MAX_QUEUE) { "This album exceeds the 500-track phone queue limit." }
        } while (rows.size == 200)
        check(tracks.isNotEmpty()) { "This album has no playable tracks. Try Shuffle albums again." }
        return Selection(album, tracks)
    }
}
