package org.guaka.radio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal data class ResolvedPlayback(
    val entries: List<Entry>,
    val index: Int,
    val positionMs: Long,
)

/** Resolves a saved ID queue while preserving its selected item when possible. */
internal object PlaybackResumption {
    suspend fun resolve(
        saved: SavedPlayback,
        tokenAvailable: Boolean,
        startingGeneration: Long,
        currentGeneration: () -> Long,
        resolver: suspend (String) -> Entry,
    ): ResolvedPlayback? {
        if (currentGeneration() != startingGeneration) return null
        val retained = saved.ids.mapIndexed { index, id -> index to id }
            .filter { (_, id) -> !id.startsWith("track:") || tokenAvailable }
        if (retained.isEmpty()) return null

        val resolved = coroutineScope {
            val semaphore = Semaphore(4)
            retained.map { (originalIndex, id) -> async {
                semaphore.withPermit {
                    try { originalIndex to resolver(id) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { null }
                }
            } }.awaitAll().filterNotNull().sortedBy { it.first }
        }
        if (resolved.isEmpty() || currentGeneration() != startingGeneration) return null

        val selected = resolved.indexOfFirst { it.first == saved.index }
            .takeIf { it >= 0 } ?: resolved.indexOfFirst { it.first > saved.index }.takeIf { it >= 0 } ?: resolved.lastIndex
        val selectedEntry = resolved[selected].second
        val position = if (resolved[selected].first != saved.index || selectedEntry.id.startsWith("radio:"))
            0L else saved.positionMs
        return ResolvedPlayback(resolved.map { it.second }, selected, position)
    }
}
