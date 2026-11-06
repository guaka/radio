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
class PlaybackPreviewStoreTest {
    private fun context(): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        return object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences("preview_test_$name", mode)
        }
    }

    @Test fun previewSurvivesStoreRecreationOnlyForTheSelectedSavedId() {
        val context = context()
        context.getSharedPreferences("listening", Context.MODE_PRIVATE).edit().clear().commit()
        val store = ListeningStore(context)
        val id = "radio:station"
        val preview = Entry(id, "Saved title", "", true, url = "https://stream.example/audio?token=secret")
        store.savePlayback(SavedPlayback(listOf("radio:other", id), 1, 0L), preview)

        val reopened = ListeningStore(context)
        // A separately constructed store uses the same test preferences and reads persisted display data.
        assertEquals("Saved title", reopened.playbackPreview()?.title)
        assertEquals("", reopened.playbackPreview()?.url)

        reopened.savePlayback(SavedPlayback(listOf("radio:other"), 0, 0L))
        assertNull(reopened.playbackPreview())
    }

    @Test fun clearAndPlexSignOutRemoveTheRelevantPreview() {
        val context = context()
        context.getSharedPreferences("listening", Context.MODE_PRIVATE).edit().clear().commit()
        val store = ListeningStore(context)
        store.savePlayback(SavedPlayback(listOf("radio:station"), 0, 0L), Entry("radio:station", "Radio", "", true))
        store.savePlayback(null)
        assertNull(store.playbackPreview())

        store.savePlayback(SavedPlayback(listOf("track:server:42"), 0, 0L), Entry("track:server:42", "Plex", "", true))
        store.clearPlex()
        assertNull(store.playbackPreview())
    }
}
