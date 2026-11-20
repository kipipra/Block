package com.focuslock.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

/**
 * LockService - Lightweight Foreground Focus Shield Service
 *
 * Keeps the focus session active in the background with a clean,
 * low-overhead status notification updated once per second.
 */
class LockService : Service() {

    companion object {
        private const val CHANNEL_ID = "focus_lock_channel"
        private const val NOTIFICATION_ID = 1001
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var notificationManager: NotificationManager
    private val handler = Handler(Looper.getMainLooper())

    private val tickerRunnable = object : Runnable {
        override fun run() {
            val isLocked = prefs.getBoolean(MainActivity.KEY_IS_LOCKED, false)
            val lockEndTime = prefs.getLong(MainActivity.KEY_LOCK_END_TIME, 0L)
            val remaining = lockEndTime - System.currentTimeMillis()

            if (!isLocked || remaining <= 0L) {
                prefs.edit().putBoolean(MainActivity.KEY_IS_LOCKED, false).apply()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }

            notificationManager.notify(NOTIFICATION_ID, buildNotification(remaining))
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val isLocked = prefs.getBoolean(MainActivity.KEY_IS_LOCKED, false)
        val lockEndTime = prefs.getLong(MainActivity.KEY_LOCK_END_TIME, 0L)
        val remaining = lockEndTime - System.currentTimeMillis()

        if (!isLocked || remaining <= 0L) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification(remaining)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        handler.removeCallbacks(tickerRunnable)
        handler.post(tickerRunnable)
        return START_STICKY
    }

    private fun buildNotification(remainingMillis: Long): Notification {
        val totalSeconds = (remainingMillis / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L

        val timeFormatted = if (hours > 0) {
            String.format("%02d:%02d:%02d remaining", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d remaining", minutes, seconds)
        }

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FocusLock Shield Active")
            .setContentText(timeFormatted)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Focus Session Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows active focus timer status"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(tickerRunnable)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
