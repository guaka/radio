package org.guaka.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlexBrowseStoreTest {
    @Test fun browseSnapshotRoundTripsTreeHistoryAndScrollPosition() {
        val saved = PlexBrowseState(
            "catalog:server-1:section-2:albums", "Albums",
            listOf(PlexBrowseLocation("plex", "Plex"), PlexBrowseLocation("server:server-1", "My server"),
                PlexBrowseLocation("library:server-1:section-2", "Music")),
            37, 125, 8L,
        )

        assertEquals(saved, PlexBrowseState.decode(PlexBrowseState.encode(saved)))
    }

    @Test fun snapshotRejectsUrlsNonPlexTreesAndOversizedScrolls() {
        assertNull(PlexBrowseState.decode("""{"parent":"https://example.org","title":"Plex","breadcrumbs":[],"scrollIndex":0,"scrollOffset":0,"plexGeneration":1}"""))
        assertNull(PlexBrowseState.decode("""{"parent":"radio","title":"Radio","breadcrumbs":[],"scrollIndex":0,"scrollOffset":0,"plexGeneration":1}"""))
        assertNull(PlexBrowseState.decode("""{"parent":"artist:server-1:42","title":"Artist","breadcrumbs":[{"id":"radio","title":"Radio"}],"scrollIndex":0,"scrollOffset":0,"plexGeneration":1}"""))
        assertNull(PlexBrowseState.decode("""{"parent":"plex","title":"Plex","breadcrumbs":[],"scrollIndex":99999,"scrollOffset":0,"plexGeneration":1}"""))
    }

    @Test fun highestRestorableIndexMatchesThePaginationRestoreLimit() {
        val state = PlexBrowseState("plex", "Plex", emptyList(), 599, 0, 1L)
        assertEquals(599, PlexBrowseState.decode(PlexBrowseState.encode(state))?.scrollIndex)
        assertNull(PlexBrowseState.decode(PlexBrowseState.encode(state).replace("599", "600")))
    }
}
