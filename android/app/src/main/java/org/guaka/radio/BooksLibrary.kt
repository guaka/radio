package org.guaka.radio

/** Local filtering for Bookstr's downloaded catalog; this never queries the public catalog. */
internal object BooksLibrary {
    fun filter(books: List<BookstrBook>, query: String): List<BookstrBook> {
        val normalized = query.trim().lowercase()
        if (normalized.isEmpty()) return books
        return books.filter { book ->
            book.title.contains(normalized, ignoreCase = true) ||
                book.author.contains(normalized, ignoreCase = true)
        }
    }

    /** Only route the fixed controls to Bookstr after it reports an active media session. */
    fun ownsPlayerControls(state: BookstrState, selectedForPlayback: Boolean): Boolean {
        val sessionState = state.playbackState in setOf(
            android.support.v4.media.session.PlaybackStateCompat.STATE_PLAYING,
            android.support.v4.media.session.PlaybackStateCompat.STATE_PAUSED,
            android.support.v4.media.session.PlaybackStateCompat.STATE_BUFFERING,
            android.support.v4.media.session.PlaybackStateCompat.STATE_CONNECTING,
        )
        return selectedForPlayback && (state.currentBookId != null && sessionState || state.playing || state.buffering)
    }

    fun activeBookId(state: BookstrState): String? = state.currentBookId
        ?: state.selectedBookId.takeIf { state.playing || state.buffering }
}
