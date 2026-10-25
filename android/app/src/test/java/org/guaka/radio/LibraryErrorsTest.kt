@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package org.guaka.radio

import androidx.media3.session.SessionError
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class LibraryErrorsTest {
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
