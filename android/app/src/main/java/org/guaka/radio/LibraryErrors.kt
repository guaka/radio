@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package org.guaka.radio

import androidx.media3.session.SessionError

/** Consistent controller errors for browsing and ID resolution; never include server response text. */
internal object LibraryErrors {
    fun code(error: Exception): Int = when {
        error is ApiRequestException && !error.assistant && error.statusCode in listOf(401, 403) ->
            SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED
        error is ApiRequestException && error.statusCode == 422 -> SessionError.ERROR_NOT_SUPPORTED
        error is ApiRequestException && error.statusCode in listOf(400, 404, 410) -> SessionError.ERROR_BAD_VALUE
        error is IllegalArgumentException || error is NoSuchElementException -> SessionError.ERROR_BAD_VALUE
        else -> SessionError.ERROR_IO
    }

    fun message(error: Exception): String = PlaybackRecovery.guidance(when (code(error)) {
        SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED -> PlaybackFailure.AUTHENTICATION
        SessionError.ERROR_NOT_SUPPORTED -> PlaybackFailure.UNSUPPORTED_MEDIA
        SessionError.ERROR_BAD_VALUE -> PlaybackFailure.NOT_FOUND
        else -> PlaybackRecovery.classify(error)
    })
}
