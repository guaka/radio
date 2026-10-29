package org.guaka.radio

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StationArtworkCacheTest {
    @org.junit.Rule @JvmField val temporary = org.junit.rules.TemporaryFolder()

    @Test fun survivesCacheRecreationWithoutNetwork() = runBlocking {
        val directory = temporary.newFolder()
        val bytes = byteArrayOf(1, 2, 3)
        StationArtworkCache(diskDirectory = directory).get("radio:doomed") { bytes }
        val reopened = StationArtworkCache(diskDirectory = directory)
        assertArrayEquals(bytes, reopened.get("radio:doomed") { error("Must use disk cache") })
    }

    @Test fun diskEvictionRespectsBudgetAndDoesNotLeavePartialFiles() = runBlocking {
        val directory = temporary.newFolder()
        val cache = StationArtworkCache(diskDirectory = directory, maxDiskBytes = 5)
        cache.get("radio:a") { byteArrayOf(1, 2, 3) }
        directory.listFiles()!!.single().setLastModified(1L)
        cache.get("radio:b") { byteArrayOf(4, 5, 6) }
        assertEquals(3L, directory.listFiles()!!.sumOf { it.length() })
        assertEquals(1, directory.listFiles()!!.size)
        assertArrayEquals(byteArrayOf(4, 5, 6), StationArtworkCache(diskDirectory = directory)
            .get("radio:b") { error("Must use disk cache") })
    }

    @Test fun expiredDiskEntryIsDownloadedAgain() = runBlocking {
        val directory = temporary.newFolder()
        StationArtworkCache(diskDirectory = directory).get("radio:a") { byteArrayOf(1) }
        directory.listFiles()!!.single().setLastModified(1L)
        assertArrayEquals(byteArrayOf(2), StationArtworkCache(diskDirectory = directory)
            .get("radio:a") { byteArrayOf(2) })
    }

    @Test fun unwritableDiskStillReturnsDownloadedImage() = runBlocking {
        val file = temporary.newFile()
        assertArrayEquals(byteArrayOf(1), StationArtworkCache(diskDirectory = file)
            .get("radio:a") { byteArrayOf(1) })
    }

    @Test fun coalescesConcurrentRequestsAndReturnsCachedBytes() = runBlocking {
        val cache = StationArtworkCache(maxBytes = 32)
        val release = CompletableDeferred<Unit>()
        var requests = 0
        val first = async {
            cache.get("radio:doomed") {
                requests++
                release.await()
                byteArrayOf(1, 2, 3)
            }
        }
        val second = async { cache.get("radio:doomed") { requests++; byteArrayOf(9) } }
        delay(20)
        release.complete(Unit)
        assertArrayEquals(byteArrayOf(1, 2, 3), first.await())
        assertArrayEquals(byteArrayOf(1, 2, 3), second.await())
        assertEquals(1, requests)
    }

    @Test fun evictsOldestItemsWithinMemoryBudget() = runBlocking {
        val cache = StationArtworkCache(maxBytes = 5)
        var requests = 0
        suspend fun fetch(key: String, bytes: ByteArray) = cache.get(key) { requests++; bytes }
        fetch("radio:a", byteArrayOf(1, 2, 3))
        fetch("radio:b", byteArrayOf(4, 5, 6))
        assertEquals(3, cache.cachedBytes())
        fetch("radio:a", byteArrayOf(7, 8, 9))
        assertEquals(3, requests)
    }

    @Test fun negativeResultsAreBrieflyCached() = runBlocking {
        var now = 0L
        val cache = StationArtworkCache(nowMs = { now }, negativeTtlMs = 100L)
        var requests = 0
        assertNull(cache.get("radio:missing") { requests++; null })
        assertNull(cache.get("radio:missing") { requests++; byteArrayOf(1) })
        assertEquals(1, requests)
        now = 101L
        assertArrayEquals(byteArrayOf(1), cache.get("radio:missing") { requests++; byteArrayOf(1) })
        assertEquals(2, requests)
    }

    @Test fun cancellationDoesNotCacheFailure() = runBlocking {
        val cache = StationArtworkCache()
        val pending = CompletableDeferred<ByteArray?>()
        val job = async { cache.get("radio:cancel") { pending.await() } }
        delay(20)
        job.cancelAndJoin()
        assertArrayEquals(byteArrayOf(2), withTimeout(1_000) {
            cache.get("radio:cancel") { byteArrayOf(2) }
        })
    }
}
