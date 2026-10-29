package org.guaka.radio

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PlexDiscoveryCacheTest {
    private val server = PlexServer("server", "Music", "old-token", emptyList())

    @Test fun accountChangeRejectsLateServerAndEndpointDiscovery() {
        val cache = PlexDiscoveryCache()
        val pending = cache.snapshot()
        cache.storeServers(pending, listOf(server))
        cache.reset()
        val replacement = server.copy(token = "new-token")
        cache.storeServers(cache.snapshot(), listOf(replacement))
        assertThrows(IOException::class.java) { cache.storeServers(pending, listOf(server)) }
        assertThrows(IOException::class.java) { cache.storeEndpoint(pending, server, "https://old.example") }
        assertThrows(IOException::class.java) { cache.requireServer(cache.snapshot(), server) }
        assertEquals(replacement, cache.server("server"))
        assertNull(cache.endpoint(replacement))
    }

    @Test fun explicitRefreshDiscardsEndpointsEvenWhenTheAccountIsUnchanged() {
        val cache = PlexDiscoveryCache()
        val old = cache.snapshot()
        cache.storeServers(old, listOf(server))
        cache.storeEndpoint(old, server, "https://old.example")
        assertEquals("https://old.example", cache.endpoint(server))
        cache.reset()
        val fresh = cache.snapshot()
        cache.storeServers(fresh, listOf(server))
        assertThrows(IOException::class.java) { cache.storeEndpoint(old, server, "https://old.example") }
        cache.storeEndpoint(fresh, server, "https://fresh.example")
        assertEquals("https://fresh.example", cache.endpoint(server))
    }
}
