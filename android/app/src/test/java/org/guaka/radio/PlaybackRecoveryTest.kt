package org.guaka.radio

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class PlaybackRecoveryTest {
    @Test fun classifiesHttpResponsesIntoRetryAndRecoveryActions() {
        assertEquals(PlaybackFailure.AUTHENTICATION, PlaybackRecovery.classifyHttpStatus(401))
        assertEquals(PlaybackFailure.AUTHENTICATION, PlaybackRecovery.classifyHttpStatus(403))
        assertEquals(PlaybackFailure.NOT_FOUND, PlaybackRecovery.classifyHttpStatus(404))
        assertEquals(PlaybackFailure.TRANSIENT_NETWORK, PlaybackRecovery.classifyHttpStatus(408))
        assertEquals(PlaybackFailure.TRANSIENT_NETWORK, PlaybackRecovery.classifyHttpStatus(429))
        assertEquals(PlaybackFailure.SERVER, PlaybackRecovery.classifyHttpStatus(503))
        assertEquals(PlaybackFailure.UNKNOWN, PlaybackRecovery.classifyHttpStatus(400))

        assertFalse(PlaybackRecovery.isRetryable(PlaybackFailure.AUTHENTICATION))
        assertFalse(PlaybackRecovery.isRetryable(PlaybackFailure.NOT_FOUND))
        assertTrue(PlaybackRecovery.isRetryable(PlaybackFailure.TRANSIENT_NETWORK))
        assertTrue(PlaybackRecovery.isRetryable(PlaybackFailure.SERVER))

        assertEquals(1_000L, PlaybackRecovery.retryDelayMs(PlaybackFailure.TRANSIENT_NETWORK, 1))
        assertEquals(2_000L, PlaybackRecovery.retryDelayMs(PlaybackFailure.SERVER, 2))
        assertEquals(4_000L, PlaybackRecovery.retryDelayMs(PlaybackFailure.SERVER, 3))
        assertEquals(null, PlaybackRecovery.retryDelayMs(PlaybackFailure.SERVER, 4))
        assertEquals(null, PlaybackRecovery.retryDelayMs(PlaybackFailure.AUTHENTICATION, 1))
    }

    @Test fun classifiesParsingFailuresAndProvidesSafeGuidance() {
        assertEquals(PlaybackFailure.UNSUPPORTED_MEDIA, PlaybackRecovery.classify(
            IOException("opaque parser failure"), PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED))
        assertEquals(PlaybackFailure.TRANSIENT_NETWORK, PlaybackRecovery.classify(IOException("connection reset")))
        assertEquals("Plex access expired. Sign in again, then choose the track again.",
            PlaybackRecovery.guidance(PlaybackFailure.AUTHENTICATION))
        assertFalse(PlaybackRecovery.guidance(PlaybackFailure.TRANSIENT_NETWORK).contains("http"))
        assertFalse(PlaybackRecovery.guidance(PlaybackFailure.TRANSIENT_NETWORK).contains("token"))
        assertEquals(3, PlaybackRecovery.MAX_RETRIES)
    }
    @Test fun certificateAndAuthenticationFailuresNeverGetTransportRetries() {
        assertEquals(PlaybackFailure.SECURE_CONNECTION, PlaybackRecovery.classify(
            IOException("outer", javax.net.ssl.SSLHandshakeException("certificate"))))
        assertFalse(PlaybackRecovery.isRetryable(PlaybackFailure.SECURE_CONNECTION))
        assertEquals(PlaybackFailure.AUTHENTICATION, PlaybackRecovery.classify(ApiRequestException(403)))
        assertEquals(null, PlaybackRecovery.retryDelayMs(PlaybackFailure.TRANSIENT_NETWORK, 0))
        assertEquals(null, PlaybackRecovery.retryDelayMs(PlaybackFailure.UNSUPPORTED_MEDIA, 1))
    }

}
