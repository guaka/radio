package org.guaka.radio

import org.junit.Assert.*
import org.junit.Test

class RadioTrackTest {
    @Test fun parsesArtistAndPreservesDashesInSongTitle() {
        assertEquals(RadioTrack("Song - Live", "Artist"), RadioTrack.parse("  Artist - Song - Live  "))
    }

    @Test fun supportsTitlesWithoutArtistsAndIgnoresEmptyUpdates() {
        assertEquals(RadioTrack("Song", null), RadioTrack.parse(" Song "))
        assertEquals(RadioTrack("Artist -", null), RadioTrack.parse("Artist - "))
        assertNull(RadioTrack.parse(null))
        assertNull(RadioTrack.parse("  "))
    }
}
