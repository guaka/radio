package org.guaka.radio

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Caches catalogue misses and throttles retries when the remote catalogue is unavailable. */
internal class SomaArtworkCache(
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val retryDelayMs: Long = DEFAULT_RETRY_DELAY_MS,
) {
    private val mutex = Mutex()
    @Volatile private var loaded: Map<String, String>? = null
    @Volatile private var retryAfterMs = 0L

    suspend fun artwork(id: String, load: suspend () -> Map<String, String>): String? {
        loaded?.let { return it[id] }
        return mutex.withLock {
            loaded?.let { return@withLock it[id] }
            if (nowMs() < retryAfterMs) return@withLock null
            try {
                val mapping = load()
                loaded = mapping
                retryAfterMs = 0L
                mapping[id]
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                retryAfterMs = nowMs() + retryDelayMs
                null
            }
        }
    }

    companion object { const val DEFAULT_RETRY_DELAY_MS = 30_000L }
}
