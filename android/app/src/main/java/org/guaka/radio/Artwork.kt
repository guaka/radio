package org.guaka.radio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/** Shared bounded decoding for list images and media-session metadata. Call on a worker thread. */
object Artwork {
    fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 256) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample })
    }

    fun thumbnail(bytes: ByteArray): ByteArray? {
        var bitmap = decode(bytes) ?: return null
        try {
            while (true) {
                for (quality in intArrayOf(85, 70, 55, 40)) {
                    val output = ByteArrayOutputStream()
                    if (bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output) && output.size() <= 32 * 1024) {
                        return output.toByteArray()
                    }
                }
                if (bitmap.width <= 32 || bitmap.height <= 32) return null
                val smaller = Bitmap.createScaledBitmap(bitmap, maxOf(32, bitmap.width / 2), maxOf(32, bitmap.height / 2), true)
                if (smaller !== bitmap) bitmap.recycle()
                bitmap = smaller
            }
        } finally { bitmap.recycle() }
    }
}
