package com.focuslock.app

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView

/**
 * LockAccessibilityService - Zero-Lag Distraction Shield
 *
 * Performance Fixes:
 * 1. In-memory SharedPreferences cache (OnSharedPreferenceChangeListener) — eliminates repeated disk reads on every scroll/window event.
 * 2. 220ms Debounced Node Traversal — prevents CPU spikes when scrolling fast inside YouTube/Instagram.
 * 3. Instant Overlay Dismissal & Smooth Fade-In — no flickering or stuck overlays.
 */
class LockAccessibilityService : AccessibilityService() {

    private lateinit var prefs: SharedPreferences
    private lateinit var windowManager: WindowManager
    private var blockOverlayView: View? = null
    private val handler = Handler(Looper.getMainLooper())

    // Cached preferences in RAM for zero-latency event checks
    @Volatile private var isLocked = false
    @Volatile private var lockEndTime = 0L
    @Volatile private var blockShorts = true
    @Volatile private var blockedApps: Set<String> = emptySet()

    private var lastShortsScanTime = 0L

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        syncPrefsToMemory()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        syncPrefsToMemory()
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
    }

    private fun syncPrefsToMemory() {
        isLocked = prefs.getBoolean(MainActivity.KEY_IS_LOCKED, false)
        lockEndTime = prefs.getLong(MainActivity.KEY_LOCK_END_TIME, 0L)
        blockShorts = prefs.getBoolean(MainActivity.KEY_BLOCK_SHORTS, true)
        blockedApps = prefs.getStringSet(MainActivity.KEY_BLOCKED_APPS, emptySet()) ?: emptySet()

        if (!isLocked || System.currentTimeMillis() >= lockEndTime) {
            removeOverlay()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // Fast-path exit when lock is inactive
        if (!isLocked) {
            removeOverlay()
            return
        }

        if (System.currentTimeMillis() >= lockEndTime) {
            prefs.edit().putBoolean(MainActivity.KEY_IS_LOCKED, false).apply()
            removeOverlay()
            return
        }

        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui") return

        // 1. Instant Full-App Block Check
        if (blockedApps.contains(pkg)) {
            showBlockOverlay("App Locked During Focus Session")
            performGlobalAction(GLOBAL_ACTION_HOME)
            return
        }

        // 2. Throttled Shorts / Reels Detection (runs at most once every 220ms)
        if (blockShorts && isShortFormHost(pkg)) {
            val now = System.currentTimeMillis()
            if (now - lastShortsScanTime > 220L) {
                lastShortsScanTime = now
                if (detectShortFormFeed(rootInActiveWindow, pkg)) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    showBlockOverlay("Short-Form Feed Blocked")
                }
            }
        }
    }

    private fun isShortFormHost(pkg: String): Boolean {
        return pkg == "com.google.android.youtube" ||
                pkg == "com.instagram.android" ||
                pkg == "com.facebook.katana" ||
                pkg == "com.zhiliaoapp.musically"
    }

    private fun detectShortFormFeed(root: AccessibilityNodeInfo?, pkg: String): Boolean {
        if (root == null) return false
        return try {
            when (pkg) {
                "com.google.android.youtube" -> {
                    val nodes = root.findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/reel_player_page_container")
                    !nodes.isNullOrEmpty()
                }
                "com.instagram.android" -> {
                    val nodes = root.findAccessibilityNodeInfosByViewId("com.instagram.android:id/clips_viewer_view_pager")
                    !nodes.isNullOrEmpty()
                }
                else -> false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun showBlockOverlay(reason: String) {
        handler.post {
            if (blockOverlayView != null) return@post

            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(Color.parseColor("#F2090B10"))
                setPadding(dp(32), dp(32), dp(32), dp(32))
                alpha = 0f
            }

            val title = TextView(this).apply {
                text = "Stay Focused"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(12) }
            }

            val subtitle = TextView(this).apply {
                text = reason
                setTextColor(Color.parseColor("#94A3B8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(28) }
            }

            val returnBtn = TextView(this).apply {
                text = "Return to FocusLock"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(24), dp(14), dp(24), dp(14))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#6366F1"))
                    cornerRadius = dp(14).toFloat()
                }
                setOnClickListener {
                    removeOverlay()
                    val launchIntent = Intent(this@LockAccessibilityService, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    startActivity(launchIntent)
                }
            }

            container.addView(title)
            container.addView(subtitle)
            container.addView(returnBtn)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )

            try {
                windowManager.addView(container, params)
                blockOverlayView = container
                container.animate()
                    .alpha(1f)
                    .setDuration(180)
                    .setInterpolator(DecelerateInterpolator())
                    .start()

                // Auto-dismiss overlay after 2.2 seconds once user is safely on Home screen
                handler.postDelayed({ removeOverlay() }, 2200L)
            } catch (_: Exception) {
            }
        }
    }

    private fun removeOverlay() {
        handler.post {
            val view = blockOverlayView ?: return@post
            blockOverlayView = null
            try {
                windowManager.removeView(view)
            } catch (_: Exception) {
            }
        }
    }

    override fun onInterrupt() {
        removeOverlay()
    }

    override fun onDestroy() {
        super.onDestroy()
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        removeOverlay()
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }
}
