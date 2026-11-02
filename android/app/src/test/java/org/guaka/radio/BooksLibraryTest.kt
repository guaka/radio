package org.guaka.radio

import org.junit.Assert.assertEquals
import org.junit.Test

class BooksLibraryTest {
    private fun book(id: String, title: String, author: String, playable: Boolean) = BookstrBook(
        id = id,
        title = title,
        author = author,
        artUri = null,
        format = if (playable) "EPUB" else "PDF",
        playable = playable,
        openReader = null,
    )

    @Test fun filtersOnlyTheDownloadedListByTitleOrAuthorWithoutDroppingUnavailableFormats() {
        val downloaded = listOf(
            book("a".repeat(64), "Dune", "Frank Herbert", true),
            book("b".repeat(64), "The Left Hand of Darkness", "Ursula Le Guin", false),
        )

        assertEquals(downloaded, BooksLibrary.filter(downloaded, "  "))
        assertEquals(listOf(downloaded[0]), BooksLibrary.filter(downloaded, "frank"))
        assertEquals(listOf(downloaded[1]), BooksLibrary.filter(downloaded, "left hand"))
        assertEquals(emptyList<BookstrBook>(), BooksLibrary.filter(downloaded, "not downloaded"))
    }

    @Test fun fixedControlsFollowBookstrSessionStateRatherThanBooksTabSelection() {
        val selectedButNotOpened = BookstrState(selectedBookId = "a".repeat(64))
        assertEquals(false, BooksLibrary.ownsPlayerControls(selectedButNotOpened, selectedForPlayback = true))

        val pausedBook = BookstrState(
            currentBookId = "a".repeat(64),
            playbackState = android.support.v4.media.session.PlaybackStateCompat.STATE_PAUSED,
        )
        assertEquals(false, BooksLibrary.ownsPlayerControls(pausedBook, selectedForPlayback = false))
        assertEquals(true, BooksLibrary.ownsPlayerControls(pausedBook, selectedForPlayback = true))

        val playingBook = BookstrState(playing = true, selectedBookId = "a".repeat(64))
        assertEquals(true, BooksLibrary.ownsPlayerControls(playingBook, selectedForPlayback = true))
    }
}
