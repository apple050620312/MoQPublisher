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
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
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
    private var baseTypes = 0
    private var projecting = false
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
                if (started && notificationsEnabled() && session.streamer.streamState.value.isActive()) {
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
                if (started && notificationsEnabled()) getSystemService(NotificationManager::class.java).notify(
                    NOTIFICATION_ID, notification(session.streamer.streamState.value, session.streamer.healthState.value)
                )
            }
        }
        scope.launch {
            session.screenCaptureState.collect { screen ->
                if (started && projecting && !screen.busy && session.streamer.streamState.value.isActive() &&
                    baseTypes and ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA != 0) {
                    runCatching { promote(session.streamer.streamState.value, projection = false) }
                        .onFailure { session.diagnostics.error("screen_capture_service_restore_failed", it) }
                }
            }
        }
        scope.launch {
            var seenEventId = session.streamer.alertState.value.history.lastOrNull()?.id ?: 0L
            var lastNotificationMs = -ALERT_COOLDOWN_MS
            var lastPriority = -1
            session.streamer.alertState.collect { state ->
                val newEvents = state.history.filter { it.id > seenEventId }
                if (newEvents.isEmpty()) return@collect
                seenEventId = newEvents.last().id
                if (!started || !notificationsEnabled()) return@collect
                val alert = newEvents.filter { it.active }.maxByOrNull { it.type.priority }
                val manager = getSystemService(NotificationManager::class.java)
                if (alert == null) {
                    if (state.active.isEmpty()) manager.cancel(ALERT_NOTIFICATION_ID)
                    return@collect
                }
                val now = SystemClock.elapsedRealtime()
                if (now - lastNotificationMs < ALERT_COOLDOWN_MS && alert.type.priority <= lastPriority) return@collect
                lastNotificationMs = now
                lastPriority = alert.type.priority
                runCatching { manager.notify(ALERT_NOTIFICATION_ID, alertNotification(alert.type, manager)) }
                    .onFailure { session.diagnostics.error("stream_alert_notification_failed", it) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (started) ownedStartId = startId
        if (intent?.action == ACTION_SCREEN_CAPTURE) {
            if (!started) {
                stopSelfResult(startId)
            } else if (session.canSwitchToScreen(intent.getLongExtra(EXTRA_GENERATION, -1))) {
                val consent = IntentCompat.getParcelableExtra(intent, EXTRA_SCREEN_CONSENT, Intent::class.java)
                val maxEdge = intent.getIntExtra(EXTRA_SCREEN_EDGE, 0)
                val fps = intent.getIntExtra(EXTRA_SCREEN_FPS, 0)
                if (consent == null || maxEdge < 2 || fps !in 1..120) return START_NOT_STICKY
                try {
                    promote(session.streamer.streamState.value, projection = true)
                    if (!session.startScreenCapture(ScreenCaptureRequest(consent, maxEdge, fps))) {
                        promote(session.streamer.streamState.value, projection = false)
                        session.cameraError.value = UiMessage(R.string.screen_capture_start_failed)
                    }
                } catch (error: Exception) {
                    session.diagnostics.error("screen_capture_service_failed", error)
                    session.cameraError.value = UiMessage(R.string.screen_capture_start_failed)
                }
            }
            return START_NOT_STICKY
        }
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
            val useCamera = config.screen == null
            if ((useCamera && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) ||
                (config.enableAudio && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED))
                throw SecurityException("Broadcast camera/microphone permission was revoked")
            baseTypes = (if (useCamera) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0) or
                (if (config.enableAudio && !config.capturePlaybackAudio) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
            promote(StreamState.Connecting, projection = config.screen != null)
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
            session.cameraError.value = (error as? UiException)?.uiMessage ?: UiMessage(R.string.error_background_start)
            session.stop(); stopSelf()
        }
        // Camera/microphone permission must be re-checked from a visible Activity after process death.
        return START_NOT_STICKY
    }

    private fun promote(state: StreamState, projection: Boolean) {
        val notice = notification(state, session.streamer.healthState.value)
        if (Build.VERSION.SDK_INT >= 29) {
            var types = if (Build.VERSION.SDK_INT >= 30) baseTypes else 0
            if (projection) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            startForeground(NOTIFICATION_ID, notice, types)
        } else startForeground(NOTIFICATION_ID, notice)
        projecting = projection
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

    private fun notificationsEnabled(): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun alertNotification(type: StreamAlertType, manager: NotificationManager): Notification {
        val localized = localizedContext()
        val prefs = getSharedPreferences("moq_stream_prefs", Context.MODE_PRIVATE)
        val mode = StreamAlertMode.fromPreference(prefs.getString(StreamAlertMode.PREFERENCE, null))
        val channel = "broadcast-alert-${mode.name.lowercase(Locale.ROOT)}"
        val sound = if (mode.sound) RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) else null
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(
                channel, localized.getString(R.string.alert_channel, localized.getString(mode.label)),
                if (mode == StreamAlertMode.SILENT) NotificationManager.IMPORTANCE_LOW else NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                setSound(sound, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build())
                enableVibration(mode.vibration)
            })
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_broadcast_notification)
            .setContentTitle(localized.getString(R.string.alert_title))
            .setContentText(localized.getString(type.label))
            .setStyle(NotificationCompat.BigTextStyle().bigText(localized.getString(type.label)))
            .setPriority(if (mode == StreamAlertMode.SILENT) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_DEFAULT)
            .setDefaults(if (mode.vibration) Notification.DEFAULT_VIBRATE else 0)
            .setSound(sound)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(20_000L)
            .build()
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
        val recovering = state is StreamState.Live && !state.canVerifyRemoteMedia()
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_broadcast_notification)
            .setContentTitle(localized.getString(R.string.broadcast_notification_title))
            .setContentText(if (recovering) baseText else healthText ?: issueText ?: audioText ?: baseText)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, localized.getString(R.string.stop_broadcast_now), stop).build()
    }

    companion object {
        private const val CHANNEL = "broadcast"
        private const val NOTIFICATION_ID = 101
        private const val ALERT_NOTIFICATION_ID = 102
        private const val ALERT_COOLDOWN_MS = 10_000L
        private const val ACTION_STOP = "com.sangege.moqpublisher.STOP_BROADCAST"
        private const val ACTION_SCREEN_CAPTURE = "com.sangege.moqpublisher.SCREEN_CAPTURE"
        private const val EXTRA_GENERATION = "broadcast_generation"
        private const val EXTRA_SCREEN_CONSENT = "screen_consent"
        private const val EXTRA_SCREEN_EDGE = "screen_edge"
        private const val EXTRA_SCREEN_FPS = "screen_fps"
        internal fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, BroadcastService::class.java))
        }

        internal fun requestScreenCapture(context: Context, generation: Long, request: ScreenCaptureRequest) {
            context.startService(Intent(context, BroadcastService::class.java).setAction(ACTION_SCREEN_CAPTURE)
                .putExtra(EXTRA_GENERATION, generation)
                .putExtra(EXTRA_SCREEN_CONSENT, request.consent)
                .putExtra(EXTRA_SCREEN_EDGE, request.maxEdge)
                .putExtra(EXTRA_SCREEN_FPS, request.fps))
        }
    }
}
