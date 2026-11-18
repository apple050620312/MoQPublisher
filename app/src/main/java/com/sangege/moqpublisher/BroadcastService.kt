package com.sangege.moqpublisher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/** Started while the Activity is visible, then owns microphone/camera work in the background. */
class BroadcastService : Service() {
    private val session get() = (application as MoqApplication).session
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false
    private var ownedGeneration = 0L
    private var ownedStartId = 0
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            session.streamer.streamState.collect { state ->
                if (started) {
                    if (state is StreamState.Live || state is StreamState.Connecting) {
                        if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this@BroadcastService,
                                Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
                            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(state))
                    } else stopSelfResult(ownedStartId)
                }
            }
        }
        scope.launch {
            session.streamer.healthState.collect { health ->
                if (started && health.level != StreamHealthLevel.IDLE &&
                    health.level != StreamHealthLevel.HEALTHY) {
                    getSystemService(NotificationManager::class.java).notify(
                        NOTIFICATION_ID, notification(session.streamer.streamState.value, health)
                    )
                }
            }
        }
        scope.launch {
            while (true) {
                delay(10 * 60 * 1_000L)
                if (started) wakeLock?.acquire(15 * 60 * 1_000L)
            }
        }
        scope.launch {
            session.streamer.audioMuted.collect {
                if (started) getSystemService(NotificationManager::class.java).notify(
                    NOTIFICATION_ID, notification(session.streamer.streamState.value, session.streamer.healthState.value)
                )
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (session.stopOwned(intent.getLongExtra(EXTRA_GENERATION, -1))) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val config = session.pendingStart
        if (config == null) {
            if (!started) stopSelf()
            return START_NOT_STICKY
        }
        try {
            ownedGeneration = session.generation
            ownedStartId = startId
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
                (config.enableAudio && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED))
                throw SecurityException("Broadcast camera/microphone permission was revoked")
            val notice = notification(StreamState.Connecting, session.streamer.healthState.value)
            if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIFICATION_ID, notice,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or (if (config.enableAudio) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0))
            else startForeground(NOTIFICATION_ID, notice)
            if (wakeLock?.isHeld == true) wakeLock?.release()
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MoQPublisher:Broadcast").apply {
                    setReferenceCounted(false); acquire(15 * 60 * 1_000L)
                }
            started = true
            session.startPending()
            session.diagnostics.event("foreground_service_started", mapOf("audio" to config.enableAudio))
        } catch (error: Throwable) {
            session.diagnostics.error("foreground_service_failed", error)
            session.cameraError.value = UiMessage(R.string.error_background_start)
            session.stop(); stopSelf()
        }
        // Camera/microphone permission must be re-checked from a visible Activity after process death.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        started = false
        if (ownedGeneration != 0L) session.stopOwned(ownedGeneration)
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun localizedContext(): Context {
        val prefs = getSharedPreferences("moq_stream_prefs", Context.MODE_PRIVATE)
        val tag = AppLanguage.fromPreference(prefs.getString("app_language", null)).tag ?: return this
        return createConfigurationContext(Configuration(resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) })
    }

    private fun notification(state: StreamState, health: StreamHealthSnapshot = StreamHealthSnapshot()): Notification {
        val localized = localizedContext()
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(
            CHANNEL, localized.getString(R.string.broadcast_notification_channel), NotificationManager.IMPORTANCE_LOW
        ))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, BroadcastService::class.java).setAction(ACTION_STOP)
            .putExtra(EXTRA_GENERATION, session.generation), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val baseText = localized.getString(when (state) {
            StreamState.Connecting -> R.string.connecting_relay
            is StreamState.Live -> state.phase.statusResource()
            else -> R.string.broadcast_notification_live
        })
        val healthText = when (health.level) {
            StreamHealthLevel.CRITICAL -> localized.getString(R.string.health_critical)
            StreamHealthLevel.WARNING -> localized.getString(R.string.health_warning)
            StreamHealthLevel.RECOVERING -> localized.getString(R.string.health_recovering)
            else -> null
        }
        val issueText = when {
            StreamHealthReason.THERMAL in health.reasons -> localized.getString(R.string.health_thermal)
            StreamHealthReason.LOW_BATTERY in health.reasons -> localized.getString(R.string.health_low_battery)
            StreamHealthReason.MEMORY_PRESSURE in health.reasons -> localized.getString(R.string.health_memory_pressure)
            else -> null
        }
        val audioText = if (session.streamer.audioMuted.value) localized.getString(R.string.broadcast_notification_muted) else null
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_broadcast_notification)
            .setContentTitle(localized.getString(R.string.broadcast_notification_title))
            .setContentText(healthText ?: issueText ?: audioText ?: baseText)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, localized.getString(R.string.stop_broadcast), stop).build()
    }

    companion object {
        private const val CHANNEL = "broadcast"
        private const val NOTIFICATION_ID = 101
        private const val ACTION_STOP = "com.sangege.moqpublisher.STOP_BROADCAST"
        private const val EXTRA_GENERATION = "broadcast_generation"
        internal fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, BroadcastService::class.java))
        }
    }
}
