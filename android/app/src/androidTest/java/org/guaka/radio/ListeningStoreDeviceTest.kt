package org.guaka.radio

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ListeningStoreDeviceTest {
    private fun isolatedContext(): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        return object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences("test_$name", mode)
        }
    }

    @Test fun accountChangeRemovesPlexStateAndKeepsRadioChoicesAcrossStoreRecreation() {
        val context = isolatedContext()
        context.getSharedPreferences("listening", Context.MODE_PRIVATE).edit().clear().commit()
        val store = ListeningStore(context)
        store.toggleFavorite("radio:7soul")
        store.toggleFavorite("track:server:42")
        store.toggleFavorite("album:server:9")
        store.savePlayback(SavedPlayback(listOf("track:server:42", "radio:7soul"), 0, 1234))
        val before = store.plexGeneration()
        store.clearPlex()
        val reopened = ListeningStore(context)
        assertEquals(before + 1, reopened.plexGeneration())
        assertEquals(listOf("radio:7soul"), reopened.favoriteIds())
        assertEquals(SavedPlayback(listOf("radio:7soul"), 0, 0L), reopened.loadPlayback())
        reopened.savePlayback(null)
        assertNull(ListeningStore(context).loadPlayback())
    }

    @Test fun historyPersistsAndPlexSignOutAndClearRemoveOnlyTheExpectedItems() {
        val context = isolatedContext()
        context.getSharedPreferences("listening", Context.MODE_PRIVATE).edit().clear().commit()
        val store = ListeningStore(context)
        store.recordPlayed("radio:7soul")
        store.recordPlayed("track:server:42")
        store.recordPlayed("youtube:aqz-KE-bpKQ")
        store.recordPlayed("radio:7soul")
        assertEquals(listOf("radio:7soul", "youtube:aqz-KE-bpKQ", "track:server:42"), ListeningStore(context).historyIds())
        store.clearPlex()
        assertEquals(listOf("radio:7soul", "youtube:aqz-KE-bpKQ"), ListeningStore(context).historyIds())
        store.clearHistory()
        assertTrue(ListeningStore(context).historyIds().isEmpty())
    }

    @Test fun pendingHistoryIsAccountScopedAndClearSurvivesProcessRecreation() {
        val context = isolatedContext()
        context.getSharedPreferences("listening", Context.MODE_PRIVATE).edit().clear().commit()
        val account = "a".repeat(64)
        val relay = "wss://history.example/"
        val store = ListeningStore(context)
        store.recordPlayed("radio:local-before")
        store.configureHistorySync(account, relay)
        store.recordPlayed("track:server:42")
        store.recordPlayed("radio:shared")
        val started = requireNotNull(store.historySync())
        assertEquals(listOf("radio:shared"), started.local.entries.map { it.id })
        assertTrue(started.dirty)
        val remote = NostrHistory.Snapshot(entries = listOf(NostrHistory.Play("youtube:dQw4w9WgXcQ", System.currentTimeMillis())))
        store.acceptRemoteHistory(started, listOf(remote))
        assertTrue(store.historyIds().contains("youtube:dQw4w9WgXcQ"))

        store.configureHistorySync("b".repeat(64), relay)
        assertFalse(store.historyIds().contains("youtube:dQw4w9WgXcQ"))
        assertNull(store.acceptRemoteHistory(started, listOf(remote)))
        assertFalse(requireNotNull(store.historySync()).dirty)
        store.configureHistorySync(account, relay)
        store.clearHistory()
        val reopened = ListeningStore(context)
        val clear = requireNotNull(reopened.historySync())
        assertTrue(clear.clearPending)
        assertTrue(reopened.historyIds().isEmpty())
        reopened.acceptRemoteHistory(clear, listOf(remote))
        assertTrue(reopened.historyIds().isEmpty())
        reopened.acknowledgeHistory(clear, 1)
        assertFalse(requireNotNull(reopened.historySync()).clearPending)
    }

    @Test fun fullFavoritesRejectNewItemsButAllowRemoval() {
        val context = isolatedContext()
        val ids = (0 until 500).map { "radio:station$it" }
        context.getSharedPreferences("listening", Context.MODE_PRIVATE).edit().clear()
            .putString("favoriteIds", ListeningStore.encodeIds(ids)).commit()
        val store = ListeningStore(context)
        assertTrue(runCatching { store.toggleFavorite("radio:oneMore") }.isFailure)
        assertEquals(500, store.favoriteIds().size)
        assertFalse(store.toggleFavorite(ids.first()))
        assertTrue(store.toggleFavorite("radio:oneMore"))
        assertTrue(ListeningStore(context).isFavorite("radio:oneMore"))
    }
}
