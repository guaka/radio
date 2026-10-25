package org.guaka.radio

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookstrControllerTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun missingBookstrPackageProducesFriendlyDisconnectedState() {
        val installed = runCatching {
            context.packageManager.getPackageInfo(BookstrProtocol.PACKAGE_NAME, 0)
        }.isSuccess
        assumeFalse("This test requires Bookstr to be absent from the device.", installed)

        val controller = BookstrController(context)
        controller.connect()

        assertFalse(controller.state.connected)
        assertFalse(controller.state.connecting)
        assertEquals("Bookstr is not installed.", controller.state.error)
        controller.close()
    }

    @Test fun catalogKeepsPdfMetadataButNeverMakesItPlayable() {
        val item = mediaItem(
            id = "a".repeat(32),
            title = "A PDF book",
            author = "Ada Reader",
            format = "pdf",
            advertisedPlayable = false,
            mediaFlags = 0,
        )

        val book = requireNotNull(mapBookstrMediaItem(item))
        assertEquals("A PDF book", book.title)
        assertEquals("Ada Reader", book.author)
        assertEquals("pdf", book.format)
        assertFalse(book.playable)
        assertNull(book.openReader)
    }

    @Test fun epubRequiresPlayableFlagAndBookstrOwnedImmutableReaderIntent() {
        val foreignIntent = readerIntent(immutable = true)
        val item = mediaItem(
            id = "b".repeat(64),
            title = "An EPUB",
            author = "A. Author",
            format = "epub",
            advertisedPlayable = true,
            mediaFlags = MediaBrowserCompat.MediaItem.FLAG_PLAYABLE,
            pendingIntent = foreignIntent,
        )

        val book = requireNotNull(mapBookstrMediaItem(item))
        assertFalse("A radio/test app PendingIntent cannot impersonate Bookstr.", book.playable)
        assertNull(book.openReader)
    }

    @Test fun epubWithoutMediaItemPlayableFlagRemainsMetadataOnly() {
        val item = mediaItem(
            id = "c".repeat(32),
            title = "Unplayable EPUB",
            author = "A. Reader",
            format = "epub",
            advertisedPlayable = true,
            mediaFlags = 0,
            pendingIntent = readerIntent(immutable = true),
        )

        val book = requireNotNull(mapBookstrMediaItem(item))
        assertFalse(book.playable)
        assertNull(book.openReader)
    }

    @Test fun rejectsUnknownFormatsAndInvalidCatalogIdsFromPlayback() {
        val unknown = mediaItem(
            id = "d".repeat(32),
            title = "Unknown",
            author = "Author",
            format = "zip",
            advertisedPlayable = true,
            mediaFlags = MediaBrowserCompat.MediaItem.FLAG_PLAYABLE,
            pendingIntent = readerIntent(immutable = true),
        )
        val malformedId = mediaItem(
            id = "../not-a-catalog-id",
            title = "Bad ID",
            author = "Author",
            format = "epub",
            advertisedPlayable = true,
            mediaFlags = MediaBrowserCompat.MediaItem.FLAG_PLAYABLE,
            pendingIntent = readerIntent(immutable = true),
        )

        assertFalse(requireNotNull(mapBookstrMediaItem(unknown)).playable)
        assertNull(mapBookstrMediaItem(malformedId))
    }

    private fun mediaItem(
        id: String,
        title: String,
        author: String,
        format: String,
        advertisedPlayable: Boolean,
        mediaFlags: Int,
        pendingIntent: PendingIntent? = null,
    ): MediaBrowserCompat.MediaItem {
        val extras = Bundle().apply {
            putString(BookstrProtocol.EXTRA_FORMAT, format)
            putBoolean(BookstrProtocol.EXTRA_PLAYABLE, advertisedPlayable)
            pendingIntent?.let { putParcelable(BookstrProtocol.EXTRA_OPEN_READER, it) }
        }
        val description = MediaDescriptionCompat.Builder()
            .setMediaId(id)
            .setTitle(title)
            .setSubtitle(author)
            .setIconUri(Uri.parse("https://covers.example.test/$id.jpg"))
            .setExtras(extras)
            .build()
        return MediaBrowserCompat.MediaItem(description, mediaFlags)
    }

    private fun readerIntent(immutable: Boolean): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (immutable && Build.VERSION.SDK_INT >= 23) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_MUTABLE
        }
        return PendingIntent.getActivity(
            context,
            nextRequestCode++,
            Intent(context, MainActivity::class.java),
            flags,
        )
    }

    private companion object {
        var nextRequestCode = 7100
    }
}
