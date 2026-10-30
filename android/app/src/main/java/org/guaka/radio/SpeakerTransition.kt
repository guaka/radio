package org.guaka.radio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class SpeakerTransitionResult(
    val target: Speaker,
    val action: SpeakerHandoffAction,
    val previousPauseFailed: Boolean,
)

/** Fence every remote side effect so a superseded request cannot pause the newer output. */
internal suspend fun transitionSpeakers(
    previous: Speaker?,
    explicitMediaSelection: Boolean,
    isCurrent: () -> Boolean,
    readSourceQueue: suspend (Speaker) -> SpeakerQueueSnapshot?,
    synchronize: suspend () -> Speaker,
    play: suspend (Speaker) -> Unit,
    transfer: suspend (SpeakerQueueSnapshot, Speaker) -> Unit,
    pausePrevious: suspend (Speaker) -> Unit,
): SpeakerTransitionResult? {
    currentCoroutineContext().ensureActive()
    if (!isCurrent()) return null
    val source = if (previous != null && !explicitMediaSelection) {
        checkNotNull(readSourceQueue(previous)) { "Could not read the current speaker queue. Playback was kept on the current speakers." }
    } else null
    currentCoroutineContext().ensureActive()
    if (!isCurrent()) return null
    val target = synchronize()
    currentCoroutineContext().ensureActive()
    if (!isCurrent()) return null
    val action = speakerHandoffAction(previous?.id == target.id, source != null, explicitMediaSelection)
    when (action) {
        SpeakerHandoffAction.KEEP -> Unit
        SpeakerHandoffAction.TRANSFER -> transfer(requireNotNull(source), target)
        SpeakerHandoffAction.PLAY -> play(target)
    }
    currentCoroutineContext().ensureActive()
    if (!isCurrent()) return null
    var pauseFailed = false
    if (action == SpeakerHandoffAction.PLAY && previous != null && previous.id != target.id) {
        try {
            pausePrevious(previous)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            pauseFailed = true
        }
    }
    currentCoroutineContext().ensureActive()
    if (!isCurrent()) return null
    return SpeakerTransitionResult(target, action, pauseFailed)
}
