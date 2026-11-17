package com.focuslock.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import java.util.Locale

class LockAccessibilityService : AccessibilityService() {

    companion object {
        /** Milliseconds timestamp when the lock ends. 0 = not locked. */
        @Volatile
        var lockEndTime: Long = 0L

        fun isLocked(): Boolean = lockEndTime > System.currentTimeMillis()
    }

    private val handler = Handler(Looper.getMainLooper())
    private var overlay: TextView? = null
    private var launcherPackage: String? = null

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
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 0
        }
        launcherPackage = currentLauncherPackage()
        if (isLocked()) showOverlay()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isLocked()) return
        val pkg = event?.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui") return
        if (pkg == launcherPackage) return
        // An app tried to come to the foreground: send the user back home
        // and cover the screen so nothing can be used.
        performGlobalAction(GLOBAL_ACTION_HOME)
        performGlobalAction(GLOBAL_ACTION_BACK)
        showOverlay()
    }

    override fun onInterrupt() {}

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
            setTextColor(Color.WHITE)
            textSize = 30f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#E6101018"))
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
