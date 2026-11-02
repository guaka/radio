package org.guaka.radio

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Serializes staged player changes and prevents stale candidates from replacing the active player. */
internal class PlaybackHandoff {
    private var generation = 0L

    fun begin(): Long = ++generation

    fun invalidate() {
        generation += 1
    }

    fun isCurrent(request: Long): Boolean = generation == request

    /** The candidate is discarded on failure, cancellation, or supersession. */
    suspend fun <T> stage(
        request: Long,
        stillValid: () -> Boolean,
        create: () -> T,
        prepare: suspend (T) -> Unit,
        commit: (T) -> Unit,
        isAttached: (T) -> Boolean,
        discard: (T) -> Unit,
    ): Boolean {
        val candidate = create()
        var committed = false
        try {
            prepare(candidate)
            currentCoroutineContext().ensureActive()
            if (!isCurrent(request) || !stillValid()) return false
            commit(candidate)
            committed = true
            return true
        } finally {
            // A commit callback can transfer the candidate to its owner and then
            // throw while finishing ancillary work. Never release an attached player.
            if (!committed && !isAttached(candidate)) discard(candidate)
        }
    }
}
