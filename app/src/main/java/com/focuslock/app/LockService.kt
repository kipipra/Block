package com.focuslock.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.util.Locale

class LockService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val notifyId = 42
    private val channelId = "lock_channel"

    private val ticker = object : Runnable {
        override fun run() {
            if (!LockAccessibilityService.isLocked()) {
                onLockFinished()
                return
            }
            val left = LockAccessibilityService.lockEndTime - System.currentTimeMillis()
            handler.postDelayed(this, left.coerceIn(50L, 1000L))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Load the saved lock first. Without this, a restarted service would think nothing is locked.
        LockAccessibilityService.init(this)
        createChannel()
        startForeground(notifyId, buildNotification())
        handler.post(ticker)
        return START_STICKY
    }

    private fun onLockFinished() {
        handler.removeCallbacks(ticker)
        LockAccessibilityService.lockEndTime = 0L
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(notifyId)
        stopSelf()
    }

    private fun updateNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(notifyId, buildNotification())
    }

    private fun buildNotification(): Notification {
        val end = LockAccessibilityService.lockEndTime
        // The system draws the countdown itself, so the notification never needs to be re-posted.
        return Notification.Builder(this, channelId)
            .setContentTitle("FocusLock active")
            .setContentText("Your phone is locked")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setWhen(end)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(channelId, "Focus lock", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
