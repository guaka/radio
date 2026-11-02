package org.guaka.radio

import android.os.Bundle
import androidx.media3.session.SessionCommand

/** Private session command used by the app to stage a new queue before replacing audible playback. */
internal object PlaybackSelectionCommand {
    const val ACTION = "org.guaka.radio.PLAY_SELECTION"
    const val KEY_MEDIA_IDS = "mediaIds"
    const val KEY_START_INDEX = "startIndex"
    const val KEY_POSITION_MS = "positionMs"
    const val KEY_RETRY_SELECTED_ONLY = "retrySelectedOnly"

    val COMMAND = SessionCommand(ACTION, Bundle.EMPTY)

    fun bundle(ids: List<String>, index: Int, positionMs: Long, retrySelectedOnly: Boolean = false): Bundle = Bundle().apply {
        putStringArrayList(KEY_MEDIA_IDS, ArrayList(ids))
        putInt(KEY_START_INDEX, index)
        putLong(KEY_POSITION_MS, positionMs)
        putBoolean(KEY_RETRY_SELECTED_ONLY, retrySelectedOnly)
    }
}
