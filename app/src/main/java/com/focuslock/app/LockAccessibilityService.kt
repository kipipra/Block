package com.focuslock.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import java.util.Locale

class LockAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        private var endTime: Long = 0L
        private var prefs: SharedPreferences? = null

        /**
         * Milliseconds timestamp when the lock ends. 0 = not locked.
         * Saved to storage on every change, so the lock survives a restart or the app being killed.
         */
        var lockEndTime: Long
            get() = endTime
            set(value) {
                endTime = value
                prefs?.edit()?.putLong("lockEnd", value)?.commit()
            }

        /** Must run before lockEndTime is read or written. Safe to call many times. */
        @Synchronized
        fun init(context: Context) {
            if (prefs != null) return
            val p = context.applicationContext.getSharedPreferences("focuslock_state", Context.MODE_PRIVATE)
            prefs = p
            val saved = p.getLong("lockEnd", 0L)
            if (saved > endTime) endTime = saved
        }

        fun isLocked(): Boolean = lockEndTime > System.currentTimeMillis()

        /** System UI popups that must never be closed while locked (unlock screen, volume). */
        private val BENIGN_UI = listOf("keyguard", "bouncer", "lockscreen", "biometric", "fingerprint", "volume", "toast")
        private val POWER_CLASS = listOf("globalactions", "poweroff", "powermenu", "shutdown", "reboot", "restart")
        private val POWER_TEXT = listOf("power off", "restart", "reboot", "shut down", "switch off", "power menu")
    }

    private val handler = Handler(Looper.getMainLooper())
    private var overlay: TextView? = null
    private var launcherPackage: String? = null
    private var lastCloseAt = 0L

    override fun onCreate() {
        super.onCreate()
        init(this)
    }

    private val ticker = object : Runnable {
        override fun run() {
            val v = overlay ?: return
            if (!isLocked()) {
                removeOverlay()
                return
            }
            v.text = remainingText()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onServiceConnected() {
        init(this)
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 0
        }
        launcherPackage = currentLauncherPackage()
        if (isLocked()) {
            // Android starts this service again after a restart, so the lock picks up where it was left.
            showOverlay()
            try {
                startForegroundService(Intent(this, LockService::class.java))
            } catch (_: Exception) {
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isLocked()) return
        val pkg = event?.packageName?.toString() ?: return
        if (pkg == packageName || pkg == launcherPackage) return
        if (pkg.contains("systemui")) {
            // The power menu (Power off / Restart) is drawn by System UI. Close it, and any other
            // System UI popup, but leave the unlock screen and volume alone.
            val cls = event.className?.toString()?.lowercase(Locale.ROOT) ?: ""
            val power = looksLikePowerMenu(cls, event)
            val benign = BENIGN_UI.any { cls.contains(it) }
            if (power || (!benign && SystemClock.uptimeMillis() - lastCloseAt > 1500L)) {
                closeSystemPopup()
            }
            return
        }
        // An app tried to come to the foreground: send the user back home
        // and cover the screen so nothing can be used.
        performGlobalAction(GLOBAL_ACTION_HOME)
        performGlobalAction(GLOBAL_ACTION_BACK)
        showOverlay()
    }

    override fun onInterrupt() {}

    private fun looksLikePowerMenu(cls: String, event: AccessibilityEvent): Boolean {
        if (POWER_CLASS.any { cls.contains(it) }) return true
        val text = event.text.joinToString(" ").lowercase(Locale.ROOT)
        return POWER_TEXT.any { text.contains(it) }
    }

    /** Presses Back and Home right away, then again a few times in case the popup was slow to appear. */
    private fun closeSystemPopup() {
        lastCloseAt = SystemClock.uptimeMillis()
        performGlobalAction(GLOBAL_ACTION_BACK)
        performGlobalAction(GLOBAL_ACTION_HOME)
        showOverlay()
        for (delay in longArrayOf(200L, 600L, 1200L)) {
            handler.postDelayed({
                if (isLocked()) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }
            }, delay)
        }
    }

    private fun remainingText(): String {
        val ms = lockEndTime - System.currentTimeMillis()
        val totalMin = (ms / 60000L).toInt()
        val sec = ((ms % 60000L) / 1000L).toInt()
        return if (totalMin >= 60) {
            val h = totalMin / 60
            val m = totalMin % 60
            String.format(Locale.US, "Phone is locked\n%d:%02d:%02d", h, m, sec)
        } else {
            String.format(Locale.US, "Phone is locked\n%d:%02d", totalMin, sec)
        }
    }

    private fun showOverlay() {
        if (overlay != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val v = TextView(this).apply {
            text = remainingText()
            setTextColor(Color.parseColor("#E0B04F"))
            typeface = Typeface.MONOSPACE
            textSize = 30f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#F50B0E14"))
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        try {
            wm.addView(v, params)
            overlay = v
            handler.post(ticker)
        } catch (_: Exception) {
        }
    }

    private fun removeOverlay() {
        overlay?.let {
            try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } catch (_: Exception) {}
        }
        overlay = null
        handler.removeCallbacks(ticker)
    }

    private fun currentLauncherPackage(): String? {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName
    }
}
