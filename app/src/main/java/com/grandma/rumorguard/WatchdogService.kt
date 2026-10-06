package com.grandma.rumorguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings

/**
 * Keeps [GuardService] enabled. A [ContentObserver] re-enables it the instant the OEM clears the
 * accessibility setting; a slow poll is a backstop. Runs foreground so the OEM doesn't kill it —
 * the OEM only clears the *setting*, it does not kill this app's process. No-op (but still running,
 * so diagnostics are accurate) until WRITE_SECURE_SETTINGS is granted over adb.
 */
class WatchdogService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var observer: ContentObserver
    private val poll = object : Runnable {
        override fun run() {
            reconcile()
            main.postDelayed(this, POLL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTE_ID, notification())
        observer = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) = reconcile()
        }
        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, observer,
        )
        main.post(poll)
        Status.watchdogRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        reconcile()
        return START_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        contentResolver.unregisterContentObserver(observer)
        Status.watchdogRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun reconcile() {
        Status.canWriteSecure = AccessibilityGuard.canWriteSecureSettings(this)
        if (AccessibilityGuard.isServiceEnabled(this)) return
        val ok = AccessibilityGuard.ensureEnabled(this)
        if (ok) Status.reEnableCount++
    }

    private fun notification(): Notification {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL, "辟谣小助手保护", NotificationManager.IMPORTANCE_MIN).apply {
                description = "保持辟谣小助手一直开着"
                setShowBadge(false)
            },
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("辟谣小助手正在保护")
            .setContentText("看视频号时随时帮你把关")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "watchdog"
        private const val NOTE_ID = 1001
        private const val POLL_MS = 4_000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, WatchdogService::class.java))
        }
    }
}
