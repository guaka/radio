package org.guaka.radio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArtworkDeviceTest {
    @Test fun artworkIsSampledAndSessionMetadataStaysWithinBinderBudget() {
        val source = Bitmap.createBitmap(2048, 1024, Bitmap.Config.ARGB_8888)
        val bytes = try {
            ByteArrayOutputStream().also { source.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally { source.recycle() }
        val decoded = requireNotNull(Artwork.decode(bytes))
        try {
            assertTrue(decoded.width <= 256)
            assertTrue(decoded.height <= 256)
            assertEquals(2, decoded.width / decoded.height)
        } finally { decoded.recycle() }
        val thumbnail = requireNotNull(Artwork.thumbnail(bytes))
        assertTrue(thumbnail.size <= 32 * 1024)
        val sessionImage = requireNotNull(BitmapFactory.decodeByteArray(thumbnail, 0, thumbnail.size))
        try { assertTrue(sessionImage.width <= 256 && sessionImage.height <= 256) }
        finally { sessionImage.recycle() }
    }

    @Test fun corruptArtworkIsIgnored() {
        assertNull(Artwork.decode(byteArrayOf(1, 2, 3)))
        assertNull(Artwork.thumbnail(byteArrayOf()))
    }
}
