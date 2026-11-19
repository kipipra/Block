package com.focuslock.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioManager
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.text.TextUtils
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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

        const val MAX_ALLOWED = 6

        /** Package names of apps the user may open while locked (max 6). Edited from the main screen before locking. */
        var allowedApps: List<String>
            get() = prefs?.getString("allowed", "")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
            set(value) {
                prefs?.edit()?.putString("allowed", value.distinct().take(MAX_ALLOWED).joinToString(","))?.apply()
            }

        /** Phone / in-call screens. Always usable so calls can be answered and ended. */
        private val CALL_PACKAGES = setOf(
            "com.android.dialer", "com.google.android.dialer", "com.android.incallui",
            "com.android.server.telecom", "com.android.phone", "com.samsung.android.incallui",
            "com.samsung.android.dialer", "com.miui.voip", "com.oplus.incallui", "com.coloros.incallui"
        )

        /** System UI popups that must never be closed while locked (unlock screen, volume). */
        private val BENIGN_UI = listOf("keyguard", "bouncer", "lockscreen", "biometric", "fingerprint", "volume", "toast")
        private val POWER_CLASS = listOf("globalactions", "poweroff", "powermenu", "shutdown", "reboot", "restart")
        private val POWER_TEXT = listOf("power off", "restart", "reboot", "shut down", "switch off", "power menu")
    }

    private val handler = Handler(Looper.getMainLooper())
    private var overlay: View? = null
    private var countdown: TextView? = null
    private var cachedSystemAllowed: Set<String> = emptySet()
    private var cachedAt = 0L
    private var launcherPackage: String? = null
    private var lastCloseAt = 0L

    override fun onCreate() {
        super.onCreate()
        init(this)
    }

    private val ticker = object : Runnable {
        override fun run() {
            val v = countdown ?: return
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
        if (pkg == packageName) return
        if (pkg == launcherPackage) {
            // Home screen: show the lock screen, which lists the allowed apps.
            showOverlay()
            return
        }
        // Apps the user picked, and phone / call screens: let them through.
        if (pkg in allowedApps || pkg in callPackages()) {
            removeOverlay()
            return
        }
        // Keyboards must work inside allowed apps.
        if (pkg in inputMethodPackages()) return
        if (pkg.contains("systemui")) {
            // While a call is ringing or active, leave System UI alone so the call can be answered.
            if (isCallActive()) return
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

    private fun isCallActive(): Boolean {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val m = am.mode
        return m == AudioManager.MODE_RINGTONE || m == AudioManager.MODE_IN_CALL || m == AudioManager.MODE_IN_COMMUNICATION
    }

    private fun callPackages(): Set<String> {
        refreshSystemCache()
        return cachedSystemAllowed
    }

    private var cachedIme: Set<String> = emptySet()

    private fun inputMethodPackages(): Set<String> {
        refreshSystemCache()
        return cachedIme
    }

    /** Looks up the default dialer and the installed keyboards. Cached for 30 seconds. */
    private fun refreshSystemCache() {
        val now = SystemClock.uptimeMillis()
        if (now - cachedAt < 30_000L && cachedSystemAllowed.isNotEmpty()) return
        cachedAt = now
        val calls = HashSet<String>(CALL_PACKAGES)
        try {
            (getSystemService(TELECOM_SERVICE) as TelecomManager).defaultDialerPackage?.let { calls.add(it) }
        } catch (_: Exception) {
        }
        cachedSystemAllowed = calls
        try {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            cachedIme = imm.enabledInputMethodList.map { it.packageName }.toSet()
        } catch (_: Exception) {
        }
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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun showOverlay() {
        if (overlay != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(Color.parseColor("#F50B0E14"))
        }
        val t = TextView(this).apply {
            text = remainingText()
            setTextColor(Color.parseColor("#E0B04F"))
            typeface = Typeface.MONOSPACE
            textSize = 30f
            gravity = Gravity.CENTER
        }
        countdown = t
        root.addView(t)

        // Allowed apps (up to 6), shown as a 3 x 2 grid. Tap one to open it.
        val apps = allowedApps.filter { packageManager.getLaunchIntentForPackage(it) != null }
        if (apps.isNotEmpty()) {
            val label = TextView(this).apply {
                text = "Allowed apps"
                setTextColor(Color.parseColor("#9AA4B8"))
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, dp(32), 0, dp(12))
            }
            root.addView(label)
            for (row in apps.chunked(3)) {
                val r = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                }
                for (pkg in row) r.addView(appCell(pkg))
                root.addView(r)
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        try {
            wm.addView(root, params)
            overlay = root
            handler.post(ticker)
        } catch (_: Exception) {
            countdown = null
        }
    }

    private fun appCell(pkg: String): View {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val icon = ImageView(this)
        try {
            icon.setImageDrawable(packageManager.getApplicationIcon(pkg))
        } catch (_: Exception) {
        }
        cell.addView(icon, LinearLayout.LayoutParams(dp(56), dp(56)))
        val name = TextView(this).apply {
            text = try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0))
            } catch (_: Exception) {
                pkg
            }
            setTextColor(Color.parseColor("#EEF0F5"))
            textSize = 12f
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        cell.addView(name, LinearLayout.LayoutParams(dp(88), ViewGroup.LayoutParams.WRAP_CONTENT))
        cell.setOnClickListener { launchAllowed(pkg) }
        return cell
    }

    private fun launchAllowed(pkg: String) {
        val i = packageManager.getLaunchIntentForPackage(pkg) ?: return
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            startActivity(i)
        } catch (_: Exception) {
            return
        }
        removeOverlay()
    }

    private fun removeOverlay() {
        overlay?.let {
            try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } catch (_: Exception) {}
        }
        overlay = null
        countdown = null
        handler.removeCallbacks(ticker)
    }

    private fun currentLauncherPackage(): String? {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName
    }
}
