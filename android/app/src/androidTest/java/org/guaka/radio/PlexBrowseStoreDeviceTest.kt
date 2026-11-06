package org.guaka.radio

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlexBrowseStoreDeviceTest {
    @Test fun browseLocationPersistsAcrossStoreInstancesAndIsScopedToPlexGeneration() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences("plex_browse_test_$name", mode)
        }
        context.getSharedPreferences("plexBrowse", Context.MODE_PRIVATE).edit().clear().commit()
        val state = PlexBrowseState("server:one", "My server", listOf(PlexBrowseLocation("plex", "Plex")), 21, 44, 3)

        PlexBrowseStore(context).save(state)

        assertEquals(state, PlexBrowseStore(context).load(3))
        assertNull(PlexBrowseStore(context).load(4))
    }

    @Test fun inactiveSnapshotWaitsForExplicitPlexReentryAndClearRemovesIt() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences("plex_browse_reentry_test_$name", mode)
        }
        context.getSharedPreferences("plexBrowse", Context.MODE_PRIVATE).edit().clear().commit()
        val store = PlexBrowseStore(context)
        val state = PlexBrowseState("server:one", "My server", listOf(PlexBrowseLocation("plex", "Plex")), 21, 44, 3)
        store.save(state)

        store.markInactive()
        assertNull(store.load(3))
        assertEquals(state, store.load(3, requireActive = false))
        store.clear()
        assertNull(store.load(3, requireActive = false))
    }
}
