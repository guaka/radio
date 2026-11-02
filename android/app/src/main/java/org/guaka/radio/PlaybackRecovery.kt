@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package org.guaka.radio

import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

enum class PlaybackFailure {
    AUTHENTICATION,
    SECURE_CONNECTION,
    NOT_FOUND,
    TRANSIENT_NETWORK,
    SERVER,
    UNSUPPORTED_MEDIA,
    UNKNOWN,
}

/** Converts playback errors into safe, actionable copy without exposing request URLs or credentials. */
object PlaybackRecovery {
    const val MAX_RETRIES = 3

    fun classify(error: Throwable?, playbackErrorCode: Int? = null): PlaybackFailure {
        val causes = generateSequence(error) { it.cause }.take(12).toList()
        causes.filterIsInstance<ApiRequestException>().firstOrNull()?.let { return classifyHttpStatus(it.statusCode) }
        if (causes.any { it is javax.net.ssl.SSLHandshakeException || it is javax.net.ssl.SSLPeerUnverifiedException })
            return PlaybackFailure.SECURE_CONNECTION
        val response = causes.filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .firstOrNull()?.responseCode
        response?.let { return classifyHttpStatus(it) }
        if (playbackErrorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
            playbackErrorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ||
            causes.any { it is UnrecognizedInputFormatException }) return PlaybackFailure.UNSUPPORTED_MEDIA
        if (causes.any { it is UnknownHostException || it is ConnectException || it is SocketTimeoutException ||
                it is SocketException || it is EOFException || it is java.io.InterruptedIOException }) {
            return PlaybackFailure.TRANSIENT_NETWORK
        }
        if (causes.any { it is IOException }) return PlaybackFailure.TRANSIENT_NETWORK
        return PlaybackFailure.UNKNOWN
    }

    fun classifyHttpStatus(code: Int): PlaybackFailure = when (code) {
        401, 403 -> PlaybackFailure.AUTHENTICATION
        404, 410 -> PlaybackFailure.NOT_FOUND
        408, 429 -> PlaybackFailure.TRANSIENT_NETWORK
        in 500..599 -> PlaybackFailure.SERVER
        else -> PlaybackFailure.UNKNOWN
    }

    fun isRetryable(failure: PlaybackFailure): Boolean =
        failure == PlaybackFailure.TRANSIENT_NETWORK || failure == PlaybackFailure.SERVER

    /** Returns the delay for this failure count, or null when retries are exhausted or unsafe. */
    fun retryDelayMs(failure: PlaybackFailure, errorCount: Int): Long? {
        if (!isRetryable(failure) || errorCount !in 1..MAX_RETRIES) return null
        val exponent = (errorCount - 1).coerceAtMost(2)
        return minOf(TimeUnit.SECONDS.toMillis(1L shl exponent), 4_000L)
    }

    fun guidance(failure: PlaybackFailure): String = when (failure) {
        PlaybackFailure.SECURE_CONNECTION -> "Secure connection failed. Check the server certificate and address."
        PlaybackFailure.AUTHENTICATION -> "Plex access expired. Sign in again, then choose the track again."
        PlaybackFailure.NOT_FOUND -> "This stream is unavailable. Choose another station or track."
        PlaybackFailure.TRANSIENT_NETWORK, PlaybackFailure.SERVER -> "Connection interrupted. Check your network and tap Retry."
        PlaybackFailure.UNSUPPORTED_MEDIA -> "This stream format is not supported. Choose another station or track."
        PlaybackFailure.UNKNOWN -> "Playback failed. Try again or choose another station or track."
    }

    /** Three bounded retries with capped exponential delays; permanent HTTP/media failures stop immediately. */
    @UnstableApi
    fun loadErrorPolicy(): LoadErrorHandlingPolicy = BoundedLoadErrorPolicy()

    @UnstableApi
    private class BoundedLoadErrorPolicy : DefaultLoadErrorHandlingPolicy(MAX_RETRIES) {
        override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            val failure = classify(info.exception)
            return retryDelayMs(failure, info.errorCount) ?: androidx.media3.common.C.TIME_UNSET
        }
    }
}
