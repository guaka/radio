package org.guaka.radio

import org.junit.Assert.assertEquals
import org.junit.Test

class TvListRowsTest {
    @Test fun favoritesComeFirstWithoutChangingOrderWithinEachGroup() {
        val folder = Entry("folder", "Folder")
        val first = Entry("station:first", "First", playable = true)
        val favorite = Entry("station:favorite", "Favorite", playable = true)
        val last = Entry("station:last", "Last", playable = true)

        assertEquals(
            listOf(favorite, folder, first, last),
            tvFavoriteFirstEntries(listOf(folder, first, favorite, last), setOf(favorite.id)),
        )
    }
}
