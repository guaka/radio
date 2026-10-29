package org.guaka.radio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

/** Bounded memory and optional disk cache for public radio artwork; Plex images stay outside this cache. */
internal class StationArtworkCache(
    private val maxBytes: Int = DEFAULT_MAX_BYTES,
    private val diskDirectory: File? = null,
    private val maxDiskBytes: Long = 32L * 1024 * 1024,
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
            readDisk(key)?.let {
                cache(key, it)
                return@withLock it
            }
            if (containsNull(key)) return@withLock null
            try {
                load().also { cache(key, it); if (it != null) writeDisk(key, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                cache(key, null)
                null
            }
        }
    }

    private fun diskFile(key: String): File? = diskDirectory?.let { directory ->
        val name = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        File(directory, "$name.img")
    }

    // Repository artwork calls run on Dispatchers.IO. Disk failures must never hide a usable download.
    @Synchronized private fun readDisk(key: String): ByteArray? = runCatching {
        val file = diskFile(key) ?: return null
        if (!file.isFile) return null
        if (file.length() !in 1..MAX_IMAGE_BYTES ||
            System.currentTimeMillis() - file.lastModified() > DISK_TTL_MS) {
            file.delete()
            return null
        }
        file.readBytes()
    }.getOrNull()

    @Synchronized private fun writeDisk(key: String, bytes: ByteArray) {
        if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES || bytes.size > maxDiskBytes) return
        runCatching {
            val target = diskFile(key) ?: return
            val directory = target.parentFile ?: return
            if (!directory.isDirectory && !directory.mkdirs()) return
            val temporary = File.createTempFile("artwork-", ".tmp", directory)
            try {
                temporary.writeBytes(bytes)
                if (!temporary.renameTo(target)) return
            } finally {
                temporary.delete()
            }
            val files = directory.listFiles { file -> file.extension == "img" }
                ?.sortedBy { it.lastModified() }.orEmpty()
            var total = files.sumOf { it.length() }
            for (file in files) {
                if (total <= maxDiskBytes) break
                val size = file.length()
                if (file.delete()) total -= size
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
        private const val MAX_IMAGE_BYTES = 1024L * 1024
        private const val DISK_TTL_MS = 30L * 24 * 60 * 60 * 1000
        const val DEFAULT_MAX_BYTES = 8 * 1024 * 1024
        const val DEFAULT_NEGATIVE_TTL_MS = 30_000L
    }
}
