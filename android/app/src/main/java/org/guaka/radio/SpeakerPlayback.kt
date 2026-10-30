package org.guaka.radio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class SpeakerPlaybackResult(val started: List<Speaker>, val failed: List<Speaker>)

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
