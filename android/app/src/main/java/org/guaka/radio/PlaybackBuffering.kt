@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package org.guaka.radio

import androidx.media3.exoplayer.DefaultLoadControl

/** Buffer thresholds for steadier network playback, with Media3's normal target byte limit. */
internal object PlaybackBuffering {
    const val MIN_BUFFER_MS = 30_000
    const val MAX_BUFFER_MS = 60_000
    const val BUFFER_FOR_PLAYBACK_MS = 3_000
    const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 6_000

    fun loadControl(): DefaultLoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            MIN_BUFFER_MS,
            MAX_BUFFER_MS,
            BUFFER_FOR_PLAYBACK_MS,
            BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
        )
        .build()
}
