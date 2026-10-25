package org.guaka.radio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

/** Bounded in-memory cache for public radio artwork; Plex images stay outside this cache. */
internal class StationArtworkCache(
    private val maxBytes: Int = DEFAULT_MAX_BYTES,
    private val negativeTtlMs: Long = DEFAULT_NEGATIVE_TTL_MS,
    private val nowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    private data class Value(val bytes: ByteArray?, val expiresAtMs: Long)
    private val values = LinkedHashMap<String, Value>(16, 0.75f, true)
    private val locks = ConcurrentHashMap<String, Mutex>()
    private var currentBytes = 0

    suspend fun get(key: String, load: suspend () -> ByteArray?): ByteArray? {
        val mutex = locks.computeIfAbsent(key) { Mutex() }
        return mutex.withLock {
            read(key)?.let { return@withLock it }
            if (containsNull(key)) return@withLock null
            try {
                load().also { cache(key, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                cache(key, null)
                null
            }
        }
    }

    @Synchronized private fun containsNull(key: String): Boolean {
        val value = values[key] ?: return false
        if (value.bytes != null || nowMs() < value.expiresAtMs) return value.bytes == null
        remove(key)
        return false
    }

    @Synchronized private fun read(key: String): ByteArray? {
        val value = values[key] ?: return null
        if (value.bytes == null) return null
        return value.bytes
    }

    @Synchronized private fun cache(key: String, bytes: ByteArray?) {
        remove(key)
        if (bytes == null) {
            values[key] = Value(null, nowMs() + negativeTtlMs)
            return
        }
        if (bytes.size > maxBytes) return
        while (currentBytes + bytes.size > maxBytes) {
            val eldest = values.entries.firstOrNull { it.value.bytes != null } ?: break
            remove(eldest.key)
        }
        values[key] = Value(bytes, Long.MAX_VALUE)
        currentBytes += bytes.size
    }

    @Synchronized private fun remove(key: String) {
        values.remove(key)?.bytes?.let { currentBytes -= it.size }
    }

    @Synchronized internal fun cachedBytes(): Int = currentBytes

    companion object {
        const val DEFAULT_MAX_BYTES = 8 * 1024 * 1024
        const val DEFAULT_NEGATIVE_TTL_MS = 30_000L
    }
}
