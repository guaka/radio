package org.guaka.radio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/** Started only for a user-selected remote playback session, including while phone audio is paused. */
class PhoneAudioRelayService : Service() {
    private var ownershipVersion = -1L
    private var wakeLock: PowerManager.WakeLock? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val relay = (application as RadioApp).repository.phoneRelay
        if (intent?.action == STOP || !relay.hasActiveSession) {
            relay.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (ownershipVersion < 0) ownershipVersion = relay.version
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Speaker audio relay", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, PhoneAudioRelayService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, CHANNEL).setSmallIcon(org.guaka.radio.R.drawable.ic_play)
            .setContentTitle("Relaying audio to speakers").setContentText("Keep this phone connected to Nebula or Wi-Fi.")
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "Stop relay", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(ID, notification)
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "radio:audio-relay").apply { acquire(6 * 60 * 60 * 1000L) }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        (application as RadioApp).repository.phoneRelay.stopSessionsIfVersion(ownershipVersion)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "speaker-audio-relay"
        private const val ID = 42
        private const val STOP = "org.guaka.radio.STOP_AUDIO_RELAY"
    }
}

internal class PhoneAudioRelay(private val context: Context) {
    private val sessions = mutableSetOf<PhoneAudioRelaySession>()
    private var active: PhoneAudioRelaySession? = null
    var version = 0L; private set
    private val watchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "radio-relay-lifetime").apply { isDaemon = true }
    }
    private var lifetimeCheck: java.util.concurrent.ScheduledFuture<*>? = null
    @Synchronized private fun expireSessions() {
        sessions.filterNot { it.isOpen }.forEach { sessions.remove(it); it.close(); if (active === it) active = null }
        if (sessions.isEmpty()) {
            lifetimeCheck?.cancel(false); lifetimeCheck = null
            context.stopService(Intent(context, PhoneAudioRelayService::class.java))
        }
    }
    @Synchronized fun stopSessionsIfVersion(owner: Long) { if (version == owner) stopSessions() }
    val hasActiveSession: Boolean @Synchronized get() = sessions.any { it.isOpen }
    @Synchronized fun prepare(entry: Entry, http: okhttp3.OkHttpClient, address: java.net.InetAddress = phoneRelayAddress(), valid: () -> Boolean): PhoneAudioRelaySession {
        val session = PhoneAudioRelaySession(entry, address, http, valid)
        sessions.add(session)
        version++
        if (lifetimeCheck == null) lifetimeCheck = watchdog.scheduleWithFixedDelay({ expireSessions() }, 60, 60, java.util.concurrent.TimeUnit.SECONDS)
        try {
            context.startForegroundService(Intent(context, PhoneAudioRelayService::class.java))
        } catch (e: Exception) { sessions.remove(session); session.close(); throw e }
        return session
    }
    @Synchronized fun activate(session: PhoneAudioRelaySession?) {
        if (session == null) { stop(); return }
        check(session in sessions && session.isOpen) { "Audio relay is no longer available." }
        active?.takeIf { it !== session }?.let { sessions.remove(it); it.close() }
        active = session
    }
    @Synchronized fun discard(session: PhoneAudioRelaySession?) {
        if (session == null || session === active) return
        sessions.remove(session)
        session.close()
        if (sessions.isEmpty()) {
            lifetimeCheck?.cancel(false); lifetimeCheck = null
            context.stopService(Intent(context, PhoneAudioRelayService::class.java))
        }
    }
    @Synchronized fun stopPlex() {
        val plexSessions = sessions.filter { it.entry.token.isNotBlank() }
        plexSessions.forEach { sessions.remove(it); it.close(); if (active === it) active = null }
        if (sessions.isEmpty()) {
            lifetimeCheck?.cancel(false); lifetimeCheck = null
            context.stopService(Intent(context, PhoneAudioRelayService::class.java))
        }
    }
    @Synchronized fun stopSessions() {
        sessions.forEach { it.close() }; sessions.clear(); active = null; version++
        lifetimeCheck?.cancel(false); lifetimeCheck = null
    }
    @Synchronized fun stop() {
        stopSessions()
        context.stopService(Intent(context, PhoneAudioRelayService::class.java))
    }
}
