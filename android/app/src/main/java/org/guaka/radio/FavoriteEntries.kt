package org.guaka.radio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/** Resolves saved IDs concurrently while preserving their user-selected order. */
internal suspend fun resolveFavoriteEntries(
    ids: List<String>,
    tokenAvailable: Boolean,
    cachedEntry: (String) -> Entry?,
    history: Boolean = false,
    maxConcurrentLookups: Int = FAVORITE_LOOKUPS,
    resolveEntry: suspend (String) -> Entry,
): List<Entry> = coroutineScope {
    val failedServers = ConcurrentHashMap.newKeySet<String>()
    val requests = Semaphore(maxConcurrentLookups.coerceAtLeast(1))

    ids.map { id ->
        async {
            cachedEntry(id)?.withoutCredentials() ?: run {
                val serverId = id.takeIf { it.startsWith("track:") || it.startsWith("album:") }
                    ?.split(':', limit = 3)?.getOrNull(1)
                if (serverId != null && (!tokenAvailable || serverId in failedServers)) {
                    unavailableFavorite(id, signIn = !tokenAvailable, history = history)
                } else {
                    requests.withPermit {
                        if (serverId != null && (!tokenAvailable || serverId in failedServers)) {
                            unavailableFavorite(id, signIn = !tokenAvailable, history = history)
                        } else try {
                            resolveEntry(id).withoutCredentials()
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            if (serverId != null && stopsServerLookups(error)) failedServers.add(serverId)
                            unavailableFavorite(id, signIn = false, history = history)
                        }
                    }
                }
            }
        }
    }.awaitAll()
}

private fun Entry.withoutCredentials() = copy(url = "", token = "")

private fun unavailableFavorite(id: String, signIn: Boolean, history: Boolean) = Entry(
    id = id,
    title = when {
        id.startsWith("youtube:") -> "Unavailable YouTube audio"
        id.startsWith("album:") -> "Unavailable Plex album"
        else -> "Unavailable Plex track"
    },
    subtitle = if (history) { if (signIn) "Sign in to Plex" else "Reconnect and try again" }
        else if (signIn) "Sign in or remove this favorite" else "Reconnect or remove this favorite",
    playable = !id.startsWith("album:"),
)

/** Missing or unsupported media affects one favorite; auth/network/server failures affect its server. */
private fun stopsServerLookups(error: Exception): Boolean = when (error) {
    is ApiRequestException -> error.statusCode in setOf(401, 403, 408, 429) || error.statusCode in 500..599
    else -> true
}

internal const val FAVORITE_LOOKUPS = 4
internal const val HISTORY_LOOKUPS = 8
