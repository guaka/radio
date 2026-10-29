package org.guaka.radio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class FavoriteEntriesTest {
    @Test fun resolvesWithBoundedConcurrencyAndKeepsInputOrder() = runBlocking {
        val ids = (0 until 12).map { "track:server:$it" }
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val results = resolveFavoriteEntries(ids, true, { null }) { id ->
            val now = active.incrementAndGet()
            maximum.updateAndGet { old -> maxOf(old, now) }
            try {
                delay(20)
                Entry(id, id.substringAfterLast(':'))
            } finally {
                active.decrementAndGet()
            }
        }

        assertEquals(ids, results.map { it.id })
        assertEquals((0 until 12).map { it.toString() }, results.map { it.title })
        assertTrue("maximum active resolver count was ${maximum.get()}", maximum.get() in 2..4)
    }

    @Test fun authOrOfflineFailureStopsQueuedLookupsForThatServer() = runBlocking {
        val ids = (0 until 12).map { "track:server:$it" }
        val calls = AtomicInteger()
        val results = resolveFavoriteEntries(ids, true, { null }) {
            calls.incrementAndGet()
            throw ApiRequestException(401)
        }

        assertEquals(1, calls.get())
        assertEquals(ids, results.map { it.id })
        assertTrue(results.all { it.title == "Unavailable Plex track" })
        assertEquals("Reconnect or remove this favorite", results.first().subtitle)

        val offlineCalls = AtomicInteger()
        val offline = resolveFavoriteEntries(ids, true, { null }) {
            offlineCalls.incrementAndGet()
            throw IOException("offline")
        }
        assertEquals(1, offlineCalls.get())
        assertTrue(offline.all { it.subtitle == "Reconnect or remove this favorite" })
    }

    @Test fun oneMissingOrUnsupportedTrackDoesNotHideOtherFavoritesOnSameServer() = runBlocking {
        val ids = listOf("track:server:missing", "track:server:unsupported", "track:server:valid")
        val results = resolveFavoriteEntries(ids, true, { null }) { id ->
            when (id) {
                ids[0] -> throw ApiRequestException(404)
                ids[1] -> throw ApiRequestException(422)
                else -> Entry(id, "Available track", playable = true)
            }
        }

        assertEquals(ids, results.map { it.id })
        assertEquals("Unavailable Plex track", results[0].title)
        assertEquals("Unavailable Plex track", results[1].title)
        assertEquals("Available track", results[2].title)
    }

    @Test fun cachedMetadataIsReturnedWithoutCredentialsAndWithoutNetwork() = runBlocking {
        val id = "track:server:42"
        val cached = Entry(id, "Saved track", playable = true, url = "https://server/media", token = "secret")
        var resolverCalled = false
        val result = resolveFavoriteEntries(listOf(id), false, { cached }) {
            resolverCalled = true
            error("cached entry should avoid network")
        }.single()

        assertEquals("Saved track", result.title)
        assertEquals("", result.url)
        assertEquals("", result.token)
        assertFalse(resolverCalled)
    }

    @Test fun missingPlexTokenCreatesSignInPlaceholdersWithoutRequests() = runBlocking {
        val ids = listOf("track:server:1", "track:server:2")
        var resolverCalled = false
        val results = resolveFavoriteEntries(ids, false, { null }) {
            resolverCalled = true
            error("no token should avoid network")
        }
        assertEquals(ids, results.map { it.id })
        assertTrue(results.all { it.subtitle == "Sign in or remove this favorite" })
        assertFalse(resolverCalled)
    }

    @Test fun cancellationPropagatesInsteadOfReturningUnavailablePlaceholder() {
        val cancelled = AtomicReference<CancellationException?>()
        runCatching {
            runBlocking {
                withTimeout(50) {
                    resolveFavoriteEntries(listOf("track:server:1"), true, { null }) {
                        awaitCancellation()
                    }
                }
            }
        }.exceptionOrNull()?.let { error ->
            var cause: Throwable? = error
            while (cause != null && cause !is CancellationException) cause = cause.cause
            if (cause is CancellationException) cancelled.set(cause)
        }
        assertNotNull("cancellation should escape favorite resolution", cancelled.get())
    }
}
