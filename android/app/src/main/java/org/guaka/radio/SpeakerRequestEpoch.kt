package org.guaka.radio

/** Invalidates stale asynchronous speaker-picker results when the user changes or dismisses the flow. */
internal class SpeakerRequestEpoch {
    private var value = 0L

    fun next(): Long = ++value

    fun isCurrent(request: Long): Boolean = request == value

    fun publishIfCurrent(request: Long, publish: () -> Unit): Boolean {
        if (!isCurrent(request)) return false
        publish()
        return true
    }
}
