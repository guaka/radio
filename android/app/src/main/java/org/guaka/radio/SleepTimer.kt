@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package org.guaka.radio

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class SleepTimerState(val token: String, val deadline: Long, val speakerIds: Set<String>)
internal val SLEEP_COMMAND = SessionCommand("org.guaka.radio.SLEEP", android.os.Bundle.EMPTY)

internal class SleepTimer(private val context: Context) {
    private val preferences = context.getSharedPreferences("sleep_timer", Context.MODE_PRIVATE)
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private fun bootCount() = android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, 0)

    @Synchronized fun state(): SleepTimerState? {
        val token = preferences.getString("token", null) ?: return null
        if (preferences.getInt("boot", -1) != bootCount()) {
            preferences.edit().clear().apply()
            return null
        }
        return SleepTimerState(token, preferences.getLong("deadline", 0), preferences.getStringSet("speakers", emptySet()).orEmpty().toSet())
    }

    @Synchronized fun start(minutes: Int, speakerIds: Set<String>) {
        require(minutes in 1..180)
        val token = UUID.randomUUID().toString()
        val deadline = SystemClock.elapsedRealtime() + minutes * 60_000L
        preferences.edit().putString("token", token).putLong("deadline", deadline).putInt("boot", bootCount())
            .putStringSet("speakers", speakerIds.toSet()).remove("error").apply()
        alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadline, pendingIntent(token))
    }

    @Synchronized fun updateSpeakers(speakerIds: Set<String>) {
        if (state() != null) preferences.edit().putStringSet("speakers", speakerIds.toSet()).apply()
    }

    @Synchronized fun cancel() {
        alarms.cancel(pendingIntent(""))
        preferences.edit().clear().apply()
    }

    @Synchronized fun claim(token: String, now: Long = SystemClock.elapsedRealtime()): SleepTimerState? {
        val current = state() ?: return null
        if (current.token != token || now < current.deadline) return null
        preferences.edit().remove("token").remove("deadline").remove("speakers").remove("boot").apply()
        alarms.cancel(pendingIntent(""))
        return current
    }

    fun failure(): String? = preferences.getString("error", null)
    fun reportFailure() {
        preferences.edit().putString("error", "Sleep timer could not pause playback.").apply()
    }

    fun observe(onChange: () -> Unit): () -> Unit {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> onChange() }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun intent(token: String) = Intent(context, SleepTimerReceiver::class.java).putExtra("token", token)
    private fun pendingIntent(token: String) = PendingIntent.getBroadcast(
        context, 0, intent(token), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

internal class SleepTimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as RadioApp
        val timer = app.sleepTimer.claim(intent.getStringExtra("token").orEmpty()) ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            try {
                withTimeout(9_000) {
                    if (timer.speakerIds.isEmpty()) {
                        pausePhone(app)
                    } else {
                        app.repository.controlSpeakers(timer.speakerIds.map { Speaker(it, it) }, "pause")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("SleepTimer", "Could not pause playback: ${e.javaClass.simpleName}")
                app.sleepTimer.reportFailure()
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun pausePhone(context: Context) {
        val token = SessionToken(context, android.content.ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        try {
            val controller = awaitFuture(future, context)
            val result = awaitFuture(controller.sendCustomCommand(SLEEP_COMMAND, android.os.Bundle.EMPTY), context)
            check(result.resultCode == SessionResult.RESULT_SUCCESS) { "Could not pause playback." }
        } finally {
            MediaController.releaseFuture(future)
        }
    }

    private suspend fun <T> awaitFuture(future: ListenableFuture<T>, context: Context): T =
        suspendCancellableCoroutine { continuation ->
            future.addListener({
                try {
                    val value = future.get()
                    if (continuation.isActive) continuation.resume(value)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }, context.mainExecutor)
            continuation.invokeOnCancellation { future.cancel(true) }
        }
}
