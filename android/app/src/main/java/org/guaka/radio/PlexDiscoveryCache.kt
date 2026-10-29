package org.guaka.radio

import java.io.IOException

/** Rejects results from discovery work started before an account change or explicit refresh. */
internal class PlexDiscoveryCache {
    internal class Snapshot {
        val servers = mutableMapOf<String, PlexServer>()
        val endpoints = mutableMapOf<PlexServer, String>()
    }
    private var current = Snapshot()

    @Synchronized fun snapshot(): Snapshot = current
    @Synchronized fun reset() { current = Snapshot() }
    @Synchronized fun server(id: String): PlexServer? = current.servers[id]
    @Synchronized fun endpoint(server: PlexServer): String? = current.endpoints[server]
    @Synchronized fun removeEndpoint(server: PlexServer) { current.endpoints.remove(server) }

    @Synchronized fun storeServers(snapshot: Snapshot, servers: List<PlexServer>) {
        requireCurrent(snapshot)
        servers.forEach { current.servers[it.id] = it }
    }

    @Synchronized fun requireServer(snapshot: Snapshot, server: PlexServer) {
        requireCurrent(snapshot)
        if (current.servers[server.id] != server) throw IOException("Plex connection changed. Try again.")
    }

    @Synchronized fun storeEndpoint(snapshot: Snapshot, server: PlexServer, endpoint: String) {
        requireServer(snapshot, server)
        current.endpoints[server] = endpoint
    }

    private fun requireCurrent(snapshot: Snapshot) {
        if (snapshot !== current) throw IOException("Plex connection changed. Try again.")
    }
}
