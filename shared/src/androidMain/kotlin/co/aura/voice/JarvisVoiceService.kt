package co.aura.voice

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
import co.aura.core.logging.AuraLogger
import co.aura.core.logging.LogCategory

/**
 * Android Foreground Service to maintain JARVIS's continuous voice session
 * when external applications (YouTube, WhatsApp, Spotify, Chrome) are launched.
 *
 * Uses foregroundServiceType="microphone" as mandated by Android 14+ (API 34+).
 * Displays a persistent status notification with an immediate "Stop" action.
 */
class JarvisVoiceService : Service() {

    companion object {
        const val CHANNEL_ID = "jarvis_voice_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "co.aura.voice.ACTION_START"
        const val ACTION_STOP = "co.aura.voice.ACTION_STOP"
        const val ACTION_UPDATE_STATUS = "co.aura.voice.ACTION_UPDATE_STATUS"
        const val EXTRA_STATUS_TEXT = "extra_status_text"

        @Volatile
        var isRunning: Boolean = false
            private set

        var onStopRequested: (() -> Unit)? = null

        fun start(context: Context) {
            try {
                val intent = Intent(context, JarvisVoiceService::class.java).apply {
                    action = ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                AuraLogger.e(LogCategory.VOICE, "[JARVIS_SERVICE] Failed to start voice service", e)
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, JarvisVoiceService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
            } catch (e: Exception) {
                AuraLogger.e(LogCategory.VOICE, "[JARVIS_SERVICE] Failed to stop voice service", e)
            }
        }

        fun updateStatus(context: Context, status: String) {
            if (!isRunning) return
            try {
                val intent = Intent(context, JarvisVoiceService::class.java).apply {
                    action = ACTION_UPDATE_STATUS
                    putExtra(EXTRA_STATUS_TEXT, status)
                }
                context.startService(intent)
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    private val notificationManager: NotificationManager? by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        isRunning = true
        VoiceSessionCoordinator.isBackgroundPersistenceActive = true
        VoiceSessionCoordinator.onStatusChanged = { statusText ->
            updateNotification(statusText)
        }
        AuraLogger.i(LogCategory.VOICE, "[JARVIS_SERVICE] Foreground voice service created.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        when (action) {
            ACTION_STOP -> {
                AuraLogger.i(LogCategory.VOICE, "[JARVIS_SERVICE] Received STOP command. Stopping service.")
                onStopRequested?.invoke()
                stopServiceCleanly()
                return START_NOT_STICKY
            }
            ACTION_UPDATE_STATUS -> {
                val status = intent?.getStringExtra(EXTRA_STATUS_TEXT) ?: "Active — Listening"
                updateNotification(status)
                return START_STICKY
            }
            else -> {
                val initialText = if (VoiceSessionCoordinator.isWakeWordMonitoringActive) {
                    "Listening for 'Wake up, JARVIS'"
                } else {
                    "Active — Ready & Listening"
                }
                val notification = buildNotification(initialText)
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(
                            NOTIFICATION_ID,
                            notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                        )
                    } else {
                        startForeground(NOTIFICATION_ID, notification)
                    }
                    AuraLogger.i(LogCategory.VOICE, "[JARVIS_SERVICE] Foreground notification active with microphone type.")
                } catch (e: Exception) {
                    AuraLogger.e(LogCategory.VOICE, "[JARVIS_SERVICE] Error calling startForeground", e)
                }
            }
        }

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "JARVIS Voice Session",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ongoing persistent session for JARVIS voice conversation and command execution"
                setShowBadge(false)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        // Tap notification to reopen JARVIS
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentPendingIntent = if (launchIntent != null) {
            PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else null

        // Stop action
        val stopIntent = Intent(this, JarvisVoiceService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        builder.setContentTitle("J.A.R.V.I.S.")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)

        if (contentPendingIntent != null) {
            builder.setContentIntent(contentPendingIntent)
        }

        return builder.build()
    }

    private fun updateNotification(statusText: String) {
        val notification = buildNotification(statusText)
        notificationManager?.notify(NOTIFICATION_ID, notification)
    }

    private fun stopServiceCleanly() {
        isRunning = false
        VoiceSessionCoordinator.isBackgroundPersistenceActive = false
        VoiceSessionCoordinator.isWakeWordMonitoringActive = false
        VoiceSessionCoordinator.onStatusChanged = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
        AuraLogger.i(LogCategory.VOICE, "[JARVIS_SERVICE] Voice service stopped cleanly.")
    }

    override fun onDestroy() {
        isRunning = false
        VoiceSessionCoordinator.isBackgroundPersistenceActive = false
        VoiceSessionCoordinator.isWakeWordMonitoringActive = false
        VoiceSessionCoordinator.onStatusChanged = null
        super.onDestroy()
        AuraLogger.i(LogCategory.VOICE, "[JARVIS_SERVICE] Foreground voice service destroyed.")
    }
}
