@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package org.guaka.radio

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackRecoveryDeviceTest {
    @Test fun installedLoadPolicyRetriesTransientFailuresButStopsForPermanentFailuresAndExhaustion() {
        val policy = PlaybackRecovery.loadErrorPolicy()
        val transient = loadErrorInfo(IOException("connection reset"), errorCount = 1)
        val server = loadErrorInfo(httpError(503), errorCount = 2)
        val authentication = loadErrorInfo(httpError(401), errorCount = 1)
        val notFound = loadErrorInfo(httpError(404), errorCount = 1)
        val exhausted = loadErrorInfo(IOException("connection reset"), errorCount = PlaybackRecovery.MAX_RETRIES + 1)

        assertEquals(1_000L, policy.getRetryDelayMsFor(transient))
        assertEquals(2_000L, policy.getRetryDelayMsFor(server))
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(authentication))
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(notFound))
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(exhausted))
    }

    private fun loadErrorInfo(exception: IOException, errorCount: Int): LoadErrorHandlingPolicy.LoadErrorInfo {
        val uri = Uri.parse("https://example.test/audio")
        val event = LoadEventInfo(1, DataSpec(uri), uri, emptyMap(), 0L, 0L, 0L)
        val media = MediaLoadData(C.DATA_TYPE_MEDIA, C.TRACK_TYPE_AUDIO, null, C.SELECTION_REASON_UNKNOWN,
            null, C.TIME_UNSET, C.TIME_UNSET)
        return LoadErrorHandlingPolicy.LoadErrorInfo(event, media, exception, errorCount)
    }

    private fun httpError(statusCode: Int): HttpDataSource.InvalidResponseCodeException {
        val uri = Uri.parse("https://example.test/audio")
        return HttpDataSource.InvalidResponseCodeException(statusCode, "HTTP $statusCode", null,
            emptyMap(), DataSpec(uri), byteArrayOf())
    }
}
