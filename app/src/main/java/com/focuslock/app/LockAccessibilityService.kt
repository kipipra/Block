
package com.focuslock.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import java.util.Locale

class LockAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        private var endTime: Long = 0L

        private var prefs: SharedPreferences? = null

        var lockEndTime: Long
            get() = endTime
            set(value) {
                endTime = value
                prefs?.edit()?.putLong("lockEnd", value)?.commit()
            }

        @Synchronized
        fun init(context: Context) {
            if (prefs != null) return

            val p = context.applicationContext.getSharedPreferences(
                "focuslock_state",
                Context.MODE_PRIVATE
            )

            prefs = p

            val saved = p.getLong("lockEnd", 0L)
            if (saved > endTime) endTime = saved
        }

        fun isLocked(): Boolean =
            lockEndTime > System.currentTimeMillis()

        private const val STATE_PREFS = "focuslock_state"
        private const val ALLOWED_APPS_KEY = "allowedApps"

        private val CALL_UI_PACKAGES = setOf(
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.incallui",
            "com.google.android.dialer",
            "com.google.android.apps.dialer",
            "com.samsung.android.incallui",
            "com.samsung.android.dialer"
        )

        fun getAllowedApps(context: Context): Set<String> =
            context.getSharedPreferences(
                STATE_PREFS,
                Context.MODE_PRIVATE
            ).getStringSet(
                ALLOWED_APPS_KEY,
                emptySet()
            )?.toSet() ?: emptySet()

        fun saveAllowedApps(
            context: Context,
            packages: Set<String>
        ) {
            context.getSharedPreferences(
                STATE_PREFS,
                Context.MODE_PRIVATE
            ).edit()
                .putStringSet(ALLOWED_APPS_KEY, packages.take(6).toSet())
                .apply()
        }

        private val BENIGN_UI = listOf(
            "keyguard",
            "bouncer",
            "lockscreen",
            "biometric",
            "fingerprint",
            "volume",
            "toast",
            "keyboard",
            "inputmethod",
            "input_method",
            "ime"
        )

        private val POWER_CLASS = listOf(
            "globalactions",
            "poweroff",
            "powermenu",
            "shutdown",
            "reboot",
            "restart"
        )

        private val POWER_TEXT = listOf(
            "power off",
            "restart",
            "reboot",
            "shut down",
            "switch off",
            "power menu"
        )
    }

    private val handler = Handler(Looper.getMainLooper())

    private var overlay: TextView? = null
    private var launcherPackage: String? = null
    private var lastCloseAt = 0L

    // The last explicitly allowed application.
    private var activeAllowedAppPackage: String? = null

    // Companion applications discovered from Android.
    private var companionPackages: Set<String> = emptySet()

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
            eventTypes =
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED

            feedbackType =
                AccessibilityServiceInfo.FEEDBACK_GENERIC

            flags =
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS

            notificationTimeout = 0
        }

        launcherPackage = currentLauncherPackage()

        refreshCompanionPackages()

        if (isLocked()) {
            showOverlay()

            try {
                startForegroundService(
                    Intent(this, LockService::class.java)
                )
            } catch (_: Exception) {
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {

        if (!isLocked()) return

        val pkg = event?.packageName?.toString() ?: return

        // Our own application and the home screen.
        // Clear the previous allowed-app session.
        if (pkg == packageName || pkg == launcherPackage) {

            activeAllowedAppPackage = null

            removeOverlay()
            return
        }

        // Allow phone and incoming-call interfaces.
        if (isPhoneUiPackage(pkg)) {
            removeOverlay()
            return
        }

        // An explicitly allowed application.
        if (getAllowedApps(this).contains(pkg)) {

            activeAllowedAppPackage = pkg

            removeOverlay()
            return
        }

        // Allow Android companion applications only when
        // an allowed application is currently being used.
        if (
            activeAllowedAppPackage != null &&
            companionPackages.contains(pkg)
        ) {
            removeOverlay()
            return
        }

        // Handle System UI separately.
        if (pkg.contains("systemui")) {

            val cls = event.className
                ?.toString()
                ?.lowercase(Locale.ROOT) ?: ""

            val power = looksLikePowerMenu(cls, event)

            val benign = BENIGN_UI.any {
                cls.contains(it)
            }

            if (power) {

                activeAllowedAppPackage = null

                closeSystemPopup()

            } else if (!benign &&
                SystemClock.uptimeMillis() - lastCloseAt > 1500L
            ) {

                activeAllowedAppPackage = null

                closeSystemPopup()

            } else {

                // Keep the current allowed-app session.
                removeOverlay()
            }

            return
        }

        // Any other application is blocked.
        // Clear the previous allowed-app session.
        activeAllowedAppPackage = null

        performGlobalAction(GLOBAL_ACTION_HOME)
        performGlobalAction(GLOBAL_ACTION_BACK)

        showOverlay()
    }

    override fun onInterrupt() {}

    /**
     * Discover applications that Android may launch as
     * companions to an allowed application.
     *
     * Includes:
     * - Enabled keyboards
     * - Camera applications
     * - Photo and video pickers
     * - Document providers
     * - Permission controllers
     * - Speech recognition
     * - Browser handlers for authentication
     */
    private fun refreshCompanionPackages() {

        val result = mutableSetOf(

            // Permission controllers
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",

            // File and document interfaces
            "com.android.documentsui",
            "com.google.android.documentsui",
            "com.android.intentresolver",
            "com.google.android.intentresolver",

            // Media providers
            "com.android.providers.media",
            "com.google.android.providers.media.module",

            // Google services UI
            "com.google.android.gms"
        )

        // 1. Discover enabled keyboards.
        try {

            val inputManager =
                getSystemService(Context.INPUT_METHOD_SERVICE)
                    as? InputMethodManager

            inputManager?.enabledInputMethodList?.forEach {
                result.add(it.packageName)
            }

        } catch (_: Exception) {
        }

        // 2. Discover camera, picker, document and
        // speech recognition applications.
        val intents = listOf(

            Intent(MediaStore.ACTION_IMAGE_CAPTURE),

            Intent(MediaStore.ACTION_VIDEO_CAPTURE),

            Intent("android.provider.action.PICK_IMAGES")
                .setType("image/*"),

            Intent("android.provider.action.PICK_IMAGES")
                .setType("video/*"),

            Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            },

            Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "video/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            },

            Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            },

            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            },

            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "video/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            },

            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            },

            Intent(Intent.ACTION_PICK).apply {
                type = "image/*"
            },

            Intent(Intent.ACTION_PICK).apply {
                type = "video/*"
            },

            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH),

            Intent(Intent.ACTION_VIEW).apply {
                data = android.net.Uri.parse("https://example.com")
                addCategory(Intent.CATEGORY_BROWSABLE)
            }
        )

        for (intent in intents) {

            try {

                val handlers = packageManager.queryIntentActivities(
                    intent,
                    PackageManager.MATCH_DEFAULT_ONLY
                )

                handlers.forEach { info ->

                    info.activityInfo?.packageName?.let {
                        result.add(it)
                    }
                }

            } catch (_: Exception) {
            }
        }

        companionPackages = result
    }

    private fun isPhoneUiPackage(pkg: String): Boolean {

        return pkg in CALL_UI_PACKAGES ||
            pkg.startsWith("com.android.phone") ||
            pkg.startsWith("com.google.android.dialer") ||
            pkg.contains("incallui")
    }

    private fun looksLikePowerMenu(
        cls: String,
        event: AccessibilityEvent
    ): Boolean {

        if (POWER_CLASS.any { cls.contains(it) }) {
            return true
        }

        val text = event.text
            .joinToString(" ")
            .lowercase(Locale.ROOT)

        return POWER_TEXT.any {
            text.contains(it)
        }
    }

    private fun closeSystemPopup() {

        activeAllowedAppPackage = null

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

            String.format(
                Locale.US,
                "Phone is locked\n%d:%02d:%02d",
                h,
                m,
                sec
            )

        } else {

            String.format(
                Locale.US,
                "Phone is locked\n%d:%02d",
                totalMin,
                sec
            )
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

            try {

                (
                    getSystemService(WINDOW_SERVICE)
                        as WindowManager
                ).removeView(it)

            } catch (_: Exception) {
            }
        }

        overlay = null

        handler.removeCallbacks(ticker)
    }

    private fun currentLauncherPackage(): String? {

        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)

        return packageManager.resolveActivity(
            intent,
            0
        )?.activityInfo?.packageName
    }
}
