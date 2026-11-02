package org.guaka.radio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class SpeakerPlaybackResult(val started: List<Speaker>, val failed: List<Speaker>)

/** A shared Music Assistant queue is required for synchronized playback. */
internal suspend fun synchronizedSpeakerTarget(
    speakers: List<Speaker>,
    previous: List<Speaker> = emptyList(),
    group: suspend (Speaker, List<Speaker>) -> Unit,
    ungroup: suspend (List<Speaker>) -> Unit = {},
    readSpeakers: suspend () -> List<Speaker>,
    wait: suspend () -> Unit,
): Speaker {
    val selected = speakers.distinctBy { it.id }
    require(selected.isNotEmpty()) { "Choose a speaker." }
    val oldLeader = previous.firstOrNull()
    val leader = selected.firstOrNull { it.id == oldLeader?.id } ?: selected.first()
    val followers = selected.filterNot { it.id == leader.id }
    val removed = if (oldLeader != null && oldLeader.id != leader.id) {
        previous.drop(1)
    } else previous.filter { old -> old.id != leader.id && selected.none { it.id == old.id } }
    if (removed.isNotEmpty()) {
        ungroup(removed)
        var detached = false
        repeat(10) {
            if (!detached) {
                currentCoroutineContext().ensureActive()
                val current = readSpeakers().firstOrNull { it.id == oldLeader?.id }
                detached = current != null && removed.none { it.id in current.groupMembers }
                if (!detached) wait()
            }
        }
        check(detached) { "Music Assistant could not remove the selected speakers from the group." }
    }
    if (followers.isEmpty()) return leader
    val removedIds = removed.map { it.id }.toSet()
    val ungrouped = followers.filterNot { it.id in leader.groupMembers && it.id !in removedIds }
    if (ungrouped.isNotEmpty()) group(leader, ungrouped)
    repeat(10) {
        currentCoroutineContext().ensureActive()
        val current = readSpeakers().firstOrNull { it.id == leader.id }
        if (current != null && followers.all { it.id in current.groupMembers }) return leader
        wait()
    }
    throw IllegalStateException("Music Assistant could not synchronize these speakers. Choose compatible speakers or a Music Assistant group.")
}

/** Independent speaker commands: a failed destination must not hide successful ones. */
internal suspend fun playOnSpeakers(
    speakers: List<Speaker>,
    play: suspend (Speaker) -> Unit,
    onStarted: (List<Speaker>) -> Unit,
): SpeakerPlaybackResult {
    val started = mutableListOf<Speaker>()
    val failed = mutableListOf<Speaker>()
    for (speaker in speakers.distinctBy { it.id }) {
        currentCoroutineContext().ensureActive()
        try {
            play(speaker)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed += speaker
            continue
        }
        currentCoroutineContext().ensureActive()
        started += speaker
        onStarted(started.toList())
    }
    return SpeakerPlaybackResult(started.toList(), failed.toList())
}

internal data class SpeakerProgress(val positionMs: Long, val durationMs: Long, val playing: Boolean, val sampledAtMs: Long = System.nanoTime() / 1_000_000) {
    fun positionAt(nowMs: Long): Long {
        val elapsed = if (playing) (nowMs - sampledAtMs).coerceAtLeast(0) else 0L
        return (positionMs + elapsed).coerceIn(0, durationMs.coerceAtLeast(positionMs))
    }
    companion object {
        fun parse(queue: org.json.JSONObject): SpeakerProgress {
            val duration = (queue.optJSONObject("current_item")?.optDouble("duration", 0.0) ?: 0.0)
                .takeIf { it.isFinite() && it > 0 } ?: 0.0
            val elapsed = queue.optDouble("corrected_elapsed_time", queue.optDouble("elapsed_time", 0.0))
                .takeIf { it.isFinite() && it >= 0 } ?: 0.0
            return SpeakerProgress((elapsed * 1000).toLong(), (duration * 1000).toLong(), queue.optString("state") == "playing")
        }
    }
}
