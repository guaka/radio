package org.guaka.radio

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SomaArtworkCacheTest {
    @Test fun cachesMissingStationIds() = runBlocking {
        var requests = 0
        val cache = SomaArtworkCache(nowMs = { 0L })
        val load = { requests++; mapOf("known" to "https://api.somafm.com/logos/256/known.png") }

        assertNull(cache.artwork("unknown", load))
        assertNull(cache.artwork("alsoUnknown", load))
        assertEquals(1, requests)
    }

    @Test fun throttlesFailuresThenRetriesAfterBackoff() = runBlocking {
        var now = 10L
        var requests = 0
        val cache = SomaArtworkCache(nowMs = { now }, retryDelayMs = 500L)
        val failing: suspend () -> Map<String, String> = { requests++; throw IOException("offline") }

        assertNull(cache.artwork("station", failing))
        assertNull(cache.artwork("station", failing))
        assertEquals(1, requests)
        now += 500L
        assertEquals("https://api.somafm.com/image.png", cache.artwork("station") {
            requests++
            mapOf("station" to "https://api.somafm.com/image.png")
        })
        assertEquals(2, requests)
    }

    @Test fun propagatesCancellation() = runBlocking {
        val cache = SomaArtworkCache(nowMs = { 0L })
        var cancelled = false
        try {
            cache.artwork("station") { throw CancellationException("cancelled") }
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertEquals("https://api.somafm.com/image.png", cache.artwork("station") {
            mapOf("station" to "https://api.somafm.com/image.png")
        })
    }
}
