@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package org.guaka.radio

import androidx.media3.session.SessionError
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException

class LibraryErrorsTest {
    @Test fun youtubeExtractionFailuresGiveSafeSourceSpecificGuidance() {
        val message = LibraryErrors.message(ParsingException("https://private.example?token=secret"))
        assertTrue(message.contains("YouTube"))
        assertTrue(message.contains("app update"))
        assertFalse(message.contains("secret"))
        assertFalse(message.contains("private.example"))
        assertTrue(LibraryErrors.message(ContentNotAvailableException("private reason")).contains("restricted"))
        assertTrue(LibraryErrors.message(RuntimeException(ParsingException("private"))).contains("YouTube"))
    }

    @Test fun extractorWrappedNetworkFailuresKeepConnectionGuidance() {
        assertEquals(PlaybackRecovery.guidance(PlaybackFailure.TRANSIENT_NETWORK),
            LibraryErrors.message(ParsingException("private", IOException("offline"))))
    }

    @Test fun youtubeBotChecksSuggestChangingNetworkWithoutAskingForPlexSignIn() {
        listOf(ReCaptchaException("private", "https://private.example"),
            SignInConfirmNotBotException("private")).forEach { error ->
            val message = LibraryErrors.message(RuntimeException(error))
            assertTrue(message.contains("bot check"))
            assertTrue(message.contains("another network"))
            assertFalse(message.contains("private"))
            assertFalse(message.contains("Plex"))
        }
    }

    @Test fun offlineAndServerFailuresDoNotAskForPlexSignIn() {
        assertEquals(SessionError.ERROR_IO, LibraryErrors.code(IOException("offline")))
        assertEquals(SessionError.ERROR_IO, LibraryErrors.code(ApiRequestException(503)))
        assertEquals(SessionError.ERROR_IO, LibraryErrors.code(ApiRequestException(401, assistant = true)))
        assertFalse(LibraryErrors.message(IOException("https://private.example?token=secret")).contains("secret"))
    }

    @Test fun plexAuthenticationMissingAndUnsupportedItemsHaveDistinctActions() {
        assertEquals(SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED, LibraryErrors.code(ApiRequestException(401)))
        assertEquals(SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED, LibraryErrors.code(ApiRequestException(403)))
        assertEquals(SessionError.ERROR_BAD_VALUE, LibraryErrors.code(ApiRequestException(404)))
        assertEquals(SessionError.ERROR_BAD_VALUE, LibraryErrors.code(NoSuchElementException("missing")))
        assertEquals(SessionError.ERROR_NOT_SUPPORTED, LibraryErrors.code(ApiRequestException(422)))
    }
}
