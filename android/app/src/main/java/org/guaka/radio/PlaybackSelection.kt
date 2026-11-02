package org.guaka.radio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** A resolved queue that can be prepared off-session before it becomes audible. */
internal data class PreparedSelection(
    val entries: List<Entry>,
    val index: Int,
    val positionMs: Long,
)

/** Resolves app selections without changing the currently attached player. */
internal object PlaybackSelection {
    suspend fun resolve(
        ids: List<String>,
        index: Int,
        positionMs: Long,
        startingGeneration: Long,
        currentGeneration: () -> Long,
        reuseEntries: Map<Int, Entry> = emptyMap(),
        allowUnavailableUnselected: Boolean = false,
        resolver: suspend (String) -> Entry,
    ): PreparedSelection {
        require(ids.isNotEmpty() && ids.size <= ListeningStore.MAX_QUEUE) { "Choose between 1 and 500 items." }
        require(index in ids.indices) { "Invalid queue selection." }
        require(positionMs in 0..ListeningStore.MAX_POSITION_MS) { "Invalid playback position." }
        require(ids.all(ListeningStore::isStableMediaId)) { "Invalid media ID." }
        check(currentGeneration() == startingGeneration) { "Plex sign-in changed. Choose your queue again." }

        val requestedIndex = index
        val resolved = coroutineScope {
            val semaphore = Semaphore(4)
            ids.mapIndexed { index, id -> async {
                semaphore.withPermit {
                    try {
                        val entry = reuseEntries[index] ?: resolver(id)
                        check(entry.id == id && entry.playable) { "The selected media is unavailable." }
                        index to entry
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (!allowUnavailableUnselected || index == requestedIndex) throw e
                        null
                    }
                }
            } }.awaitAll()
        }.filterNotNull().sortedBy { it.first }
        check(currentGeneration() == startingGeneration) { "Plex sign-in changed. Choose your queue again." }
        val selectedIndex = resolved.indexOfFirst { it.first == requestedIndex }
        check(selectedIndex >= 0) { "The selected media is unavailable." }
        val entries = resolved.map { it.second }
        return PreparedSelection(entries, selectedIndex,
            if (entries[selectedIndex].id.startsWith("radio:")) 0L else positionMs)
    }
}
