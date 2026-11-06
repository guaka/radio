@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package org.guaka.radio

import androidx.media3.session.SessionError
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException

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

    fun message(error: Exception): String {
        val causes = generateSequence<Throwable>(error) { it.cause }.take(12).toList()
        if (causes.any { it is ReCaptchaException || it is SignInConfirmNotBotException }) {
            return "YouTube blocked this connection with a bot check. Try another network, then retry playback."
        }
        if (causes.any { it is ContentNotAvailableException }) {
            return "YouTube says this video is unavailable or restricted. Choose another video."
        }
        // Transport failures can be wrapped by the extractor. Keep their network guidance.
        if (causes.any { it is ExtractionException } && PlaybackRecovery.classify(error) == PlaybackFailure.UNKNOWN) {
            return "Could not load YouTube audio. Retry playback; if all videos fail, check for an app update or try another network."
        }
        return PlaybackRecovery.guidance(when (code(error)) {
            SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED -> PlaybackFailure.AUTHENTICATION
            SessionError.ERROR_NOT_SUPPORTED -> PlaybackFailure.UNSUPPORTED_MEDIA
            SessionError.ERROR_BAD_VALUE -> PlaybackFailure.NOT_FOUND
            else -> PlaybackRecovery.classify(error)
        })
    }
}
