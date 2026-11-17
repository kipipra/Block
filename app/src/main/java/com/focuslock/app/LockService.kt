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
            updateNotification()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
        val ms = (LockAccessibilityService.lockEndTime - System.currentTimeMillis()).coerceAtLeast(0)
        val min = ms / 60000
        val sec = (ms % 60000) / 1000
        return Notification.Builder(this, channelId)
            .setContentTitle("FocusLock active")
            .setContentText(String.format(Locale.US, "Locked for %d:%02d more", min, sec))
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
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
