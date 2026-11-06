@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package org.guaka.radio

import androidx.media3.exoplayer.DefaultLoadControl

/** Read ahead across network slowdowns without bypassing Media3's normal target byte limit. */
internal object PlaybackBuffering {
    // Refill before the reserve becomes small, rather than waiting until repeated short stalls.
    // At 128 kbit/s, three minutes of audio is about 2.9 MB. Startup still needs only 3 seconds.
    const val MIN_BUFFER_MS = 90_000
    const val MAX_BUFFER_MS = 180_000
    const val BUFFER_FOR_PLAYBACK_MS = 3_000
    const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 10_000

    fun loadControl(): DefaultLoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            MIN_BUFFER_MS,
            MAX_BUFFER_MS,
            BUFFER_FOR_PLAYBACK_MS,
            BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
        )
        .build()
}
