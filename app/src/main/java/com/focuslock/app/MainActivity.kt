package com.focuslock.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * MainActivity - FocusLock Pro
 *
 * Engineered for 120Hz buttery-smooth performance:
 * 1. Asynchronous Background App Loading + LRU Icon Caching (eliminates main-thread UI freezes).
 * 2. RecyclerView with DiffUtil & ViewHolder pattern (replaces laggy LinearLayout loops).
 * 3. Hardware-Accelerated Spring Animations & Tactile Haptics.
 * 4. Elevated Glass/Obsidian Surface Design with smooth live countdown progress ring.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME = "FocusLockPrefs"
        const val KEY_BLOCKED_APPS = "blocked_apps"
        const val KEY_IS_LOCKED = "is_locked"
        const val KEY_LOCK_END_TIME = "lock_end_time"
        const val KEY_LOCK_TOTAL_DURATION = "lock_total_duration"
        const val KEY_STRICT_MODE = "strict_mode"
        const val KEY_BLOCK_SHORTS = "block_shorts"

        // High-speed thread pool for icon decoding & package queries
        private val bgExecutor = Executors.newFixedThreadPool(3)

        // Memory-safe LRU Cache for pre-scaled 44dp app icons (eliminates GC spikes during scrolling)
        private val iconCache: LruCache<String, Bitmap> by lazy {
            val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
            val cacheSize = maxMemory / 8
            object : LruCache<String, Bitmap>(cacheSize) {
                override fun sizeOf(key: String, bitmap: Bitmap): Int {
                    return bitmap.byteCount / 1024
                }
            }
        }
    }

    // Modern Obsidian & Indigo Palette
    private val colorBgPrimary = Color.parseColor("#090B10")
    private val colorSurface = Color.parseColor("#131722")
    private val colorSurfaceElevated = Color.parseColor("#1C2232")
    private val colorAccent = Color.parseColor("#6366F1")      // Vibrant Indigo
    private val colorAccentGlow = Color.parseColor("#818CF8")
    private val colorSuccess = Color.parseColor("#10B981")     // Emerald
    private val colorDanger = Color.parseColor("#EF4444")      // Crimson
    private val colorWarning = Color.parseColor("#F59E0B")     // Amber
    private val colorTextPrimary = Color.parseColor("#F8FAFC")
    private val colorTextSecondary = Color.parseColor("#94A3B8")
    private val colorBorder = Color.parseColor("#262F45")

    private lateinit var prefs: SharedPreferences
    private val mainHandler = Handler(Looper.getMainLooper())

    // Data state
    private var allInstalledApps: List<AppItem> = emptyList()
    private val blockedPackages = mutableSetOf<String>()
    private var currentSearchQuery = ""
    private var selectedDurationMinutes = 30

    // UI References
    private lateinit var appAdapter: SmoothAppAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var loadingSpinner: ProgressBar
    private lateinit var searchInput: EditText
    private lateinit var selectedCountBadge: TextView
    private lateinit var startFocusBtn: TextView
    private lateinit var activeLockOverlay: LinearLayout
    private lateinit var timerDisplayText: TextView
    private lateinit var timerSubText: TextView
    private lateinit var circularProgressView: SmoothProgressRing
    private lateinit var permissionBanner: LinearLayout
    private lateinit var permissionStatusText: TextView

    private var activeTimer: CountDownTimer? = null

    data class AppItem(
        val packageName: String,
        val appName: String,
        val isBlocked: Boolean
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge rendering with hardware acceleration
        window.setFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        )
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = colorBgPrimary

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadSavedState()

        val rootView = buildMainUI()
        setContentView(rootView)

        // Handle system bar insets cleanly
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        // Start async app discovery immediately without blocking the first frame
        loadInstalledAppsAsync()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionBanner()
        checkAndSyncActiveSession()
    }

    override fun onDestroy() {
        super.onDestroy()
        activeTimer?.cancel()
    }

    private fun loadSavedState() {
        val savedSet = prefs.getStringSet(KEY_BLOCKED_APPS, emptySet()) ?: emptySet()
        blockedPackages.clear()
        blockedPackages.addAll(savedSet)
    }

    private fun saveBlockedPackages() {
        // Use apply() for asynchronous disk writes (never commit() on main thread!)
        prefs.edit().putStringSet(KEY_BLOCKED_APPS, blockedPackages.toSet()).apply()
    }

    /**
     * Builds the entire responsive, hardware-accelerated UI hierarchy.
     */
    private fun buildMainUI(): View {
        val rootContainer = FrameLayout(this).apply {
            setBackgroundColor(colorBgPrimary)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Main Scrollable / List Layout
        val mainContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }

        // 1. Top Header Bar
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }

        val titleStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val appTitle = TextView(this).apply {
            text = "FocusLock"
            setTextColor(colorTextPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = -0.02f
        }

        val appSubtitle = TextView(this).apply {
            text = "Deep work distraction shield"
            setTextColor(colorTextSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }

        titleStack.addView(appTitle)
        titleStack.addView(appSubtitle)

        selectedCountBadge = TextView(this).apply {
            text = "${blockedPackages.size} Blocked"
            setTextColor(colorAccentGlow)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = createRoundedDrawable(
                Color.parseColor("#1E243B"),
                dp(20).toFloat(),
                dp(1),
                colorAccent
            )
        }

        headerRow.addView(titleStack)
        headerRow.addView(selectedCountBadge)
        mainContent.addView(headerRow)

        // 2. Compact Permission Health Banner
        permissionBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = createRoundedDrawable(
                Color.parseColor("#2A1B1E"),
                dp(14).toFloat(),
                dp(1),
                colorDanger
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
            setOnClickListener {
                performTactileTap(it)
                openMissingPermissions()
            }
        }

        permissionStatusText = TextView(this).apply {
            text = "Action Required: Enable Accessibility & Overlay"
            setTextColor(Color.parseColor("#FCA5A5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val fixBtn = TextView(this).apply {
            text = "FIX →"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = createRoundedDrawable(colorDanger, dp(8).toFloat())
        }

        permissionBanner.addView(permissionStatusText)
        permissionBanner.addView(fixBtn)
        mainContent.addView(permissionBanner)

        // 3. Duration Selector Card + Quick Toggles
        val controlCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = createRoundedDrawable(colorSurface, dp(18).toFloat(), dp(1), colorBorder)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }

        val durationLabel = TextView(this).apply {
            text = "FOCUS DURATION"
            setTextColor(colorTextSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        controlCard.addView(durationLabel)

        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }

        val durations = listOf(15 to "15m", 30 to "30m", 60 to "1h", 120 to "2h", 240 to "4h")
        val chipViews = mutableListOf<TextView>()

        durations.forEachIndexed { index, (mins, label) ->
            val chip = TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(9), 0, dp(9))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index < durations.size - 1) marginEnd = dp(8)
                }
            }
            chipViews.add(chip)
            chipRow.addView(chip)
        }

        fun updateChipSelection() {
            durations.forEachIndexed { idx, (mins, _) ->
                val isSelected = mins == selectedDurationMinutes
                val chip = chipViews[idx]
                chip.setTextColor(if (isSelected) Color.WHITE else colorTextSecondary)
                chip.background = createRoundedDrawable(
                    if (isSelected) colorAccent else colorSurfaceElevated,
                    dp(10).toFloat(),
                    dp(1),
                    if (isSelected) colorAccentGlow else colorBorder
                )
            }
        }

        durations.forEachIndexed { idx, (mins, _) ->
            chipViews[idx].setOnClickListener {
                performTactileTap(it)
                selectedDurationMinutes = mins
                updateChipSelection()
            }
        }
        updateChipSelection()
        controlCard.addView(chipRow)

        // Quick Shield Toggles (Strict Mode + Shorts/Reels Blocker)
        val togglesRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val shortsToggle = createPillToggle(
            "Block Shorts/Reels",
            prefs.getBoolean(KEY_BLOCK_SHORTS, true)
        ) { enabled ->
            prefs.edit().putBoolean(KEY_BLOCK_SHORTS, enabled).apply()
        }

        val strictToggle = createPillToggle(
            "Strict Lock",
            prefs.getBoolean(KEY_STRICT_MODE, true)
        ) { enabled ->
            prefs.edit().putBoolean(KEY_STRICT_MODE, enabled).apply()
        }

        togglesRow.addView(shortsToggle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(8)
        })
        togglesRow.addView(strictToggle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        controlCard.addView(togglesRow)

        mainContent.addView(controlCard)

        // 4. Smooth Search Bar
        searchInput = EditText(this).apply {
            hint = "Search installed apps..."
            setHintTextColor(colorTextSecondary)
            setTextColor(colorTextPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setSingleLine(true)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = createRoundedDrawable(colorSurface, dp(14).toFloat(), dp(1), colorBorder)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }

            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    currentSearchQuery = s?.toString()?.trim() ?: ""
                    filterAndSubmitApps()
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        mainContent.addView(searchInput)

        // 5. High-Performance Recycled App List Container
        val listFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        appAdapter = SmoothAppAdapter { appItem ->
            toggleAppBlockState(appItem)
        }

        recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = appAdapter
            setHasFixedSize(true)
            setItemViewCacheSize(24)
            overScrollMode = View.OVER_SCROLL_ALWAYS
            clipToPadding = false
            setPadding(0, dp(4), 0, dp(84)) // Extra bottom space so floating CTA never covers items
            itemAnimator = null // Prevents blink/stutter when toggling checkboxes rapidly
        }

        loadingSpinner = ProgressBar(this).apply {
            indeterminateTintList = ColorStateList.valueOf(colorAccent)
            layoutParams = FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER)
        }

        listFrame.addView(recyclerView)
        listFrame.addView(loadingSpinner)
        mainContent.addView(listFrame)

        rootContainer.addView(mainContent)

        // 6. Floating Bottom Action CTA Button
        startFocusBtn = TextView(this).apply {
            text = "START FOCUS SESSION"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.05f
            elevation = dp(10).toFloat()
            setPadding(dp(24), dp(16), dp(24), dp(16))
            background = createRippleDrawable(colorAccent, colorAccentGlow, dp(16).toFloat())
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ).apply {
                setMargins(dp(18), 0, dp(18), dp(16))
            }
            setOnClickListener {
                performTactileTap(it)
                startFocusSession()
            }
        }
        rootContainer.addView(startFocusBtn)

        // 7. Full-Screen Active Lock Screen (Smooth Radial Ring + Calm UI)
        activeLockOverlay = buildActiveLockOverlay()
        rootContainer.addView(activeLockOverlay)

        return rootContainer
    }

    /**
     * Builds the serene, ultra-smooth active session shield screen.
     */
    private fun buildActiveLockOverlay(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(colorBgPrimary)
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            elevation = dp(24).toFloat()
            setPadding(dp(28), dp(28), dp(28), dp(28))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            val shieldBadge = TextView(this@MainActivity).apply {
                text = "● FOCUS SHIELD ACTIVE"
                setTextColor(colorSuccess)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.1f
                setPadding(dp(14), dp(6), dp(14), dp(6))
                background = createRoundedDrawable(
                    Color.parseColor("#0D2820"),
                    dp(20).toFloat(),
                    dp(1),
                    colorSuccess
                )
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(32) }
            }
            addView(shieldBadge)

            // Custom Smooth 60/120fps Canvas Progress Ring
            val ringContainer = FrameLayout(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(240), dp(240)).apply {
                    bottomMargin = dp(32)
                }
            }

            circularProgressView = SmoothProgressRing(this@MainActivity).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            val timerCenterStack = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            }

            timerDisplayText = TextView(this@MainActivity).apply {
                text = "30:00"
                setTextColor(colorTextPrimary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 42f)
                typeface = Typeface.create("sans-serif-light", Typeface.BOLD)
            }

            timerSubText = TextView(this@MainActivity).apply {
                text = "REMAINING"
                setTextColor(colorTextSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.12f
            }

            timerCenterStack.addView(timerDisplayText)
            timerCenterStack.addView(timerSubText)

            ringContainer.addView(circularProgressView)
            ringContainer.addView(timerCenterStack)
            addView(ringContainer)

            val motivationalNote = TextView(this@MainActivity).apply {
                text = "Distracting apps are locked.\nStay present and finish what matters."
                gravity = Gravity.CENTER
                setTextColor(colorTextSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setLineSpacing(dp(4).toFloat(), 1f)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(36) }
            }
            addView(motivationalNote)

            val emergencyUnlockBtn = TextView(this@MainActivity).apply {
                text = "End Session Early"
                setTextColor(colorTextSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(20), dp(12), dp(20), dp(12))
                background = createRoundedDrawable(colorSurface, dp(12).toFloat(), dp(1), colorBorder)
                setOnClickListener {
                    if (prefs.getBoolean(KEY_STRICT_MODE, true)) {
                        Toast.makeText(
                            this@MainActivity,
                            "Strict Lock is enabled. Wait for timer completion.",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        stopFocusSession()
                    }
                }
            }
            addView(emergencyUnlockBtn)
        }
    }

    /**
     * Loads installed launchable apps on a background thread so UI renders instantaneously.
     */
    private fun loadInstalledAppsAsync() {
        loadingSpinner.visibility = View.VISIBLE
        bgExecutor.execute {
            val pm = packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }

            val resolvedInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(mainIntent, 0)
            }

            val appMap = LinkedHashMap<String, AppItem>()
            for (info in resolvedInfos) {
                val pkg = info.activityInfo?.packageName ?: continue
                if (pkg == packageName) continue
                if (!appMap.containsKey(pkg)) {
                    val label = info.loadLabel(pm)?.toString() ?: pkg
                    appMap[pkg] = AppItem(
                        packageName = pkg,
                        appName = label,
                        isBlocked = blockedPackages.contains(pkg)
                    )
                }
            }

            // Sort blocked apps to the top, then alphabetically
            val sortedList = appMap.values.sortedWith(
                compareByDescending<AppItem> { it.isBlocked }.thenBy { it.appName.lowercase() }
            )

            mainHandler.post {
                loadingSpinner.visibility = View.GONE
                allInstalledApps = sortedList
                filterAndSubmitApps()
            }
        }
    }

    private fun filterAndSubmitApps() {
        val filtered = if (currentSearchQuery.isEmpty()) {
            allInstalledApps.map { it.copy(isBlocked = blockedPackages.contains(it.packageName)) }
        } else {
            allInstalledApps.filter {
                it.appName.contains(currentSearchQuery, ignoreCase = true) ||
                        it.packageName.contains(currentSearchQuery, ignoreCase = true)
            }.map { it.copy(isBlocked = blockedPackages.contains(it.packageName)) }
        }
        appAdapter.submitList(filtered)
        selectedCountBadge.text = "${blockedPackages.size} Blocked"
    }

    private fun toggleAppBlockState(item: AppItem) {
        if (blockedPackages.contains(item.packageName)) {
            blockedPackages.remove(item.packageName)
        } else {
            blockedPackages.add(item.packageName)
        }
        saveBlockedPackages()
        filterAndSubmitApps()
    }

    private fun startFocusSession() {
        if (!hasAllRequiredPermissions()) {
            openMissingPermissions()
            return
        }
        if (blockedPackages.isEmpty() && !prefs.getBoolean(KEY_BLOCK_SHORTS, true)) {
            Toast.makeText(this, "Select at least one app to block", Toast.LENGTH_SHORT).show()
            return
        }

        val durationMillis = selectedDurationMinutes * 60 * 1000L
        val endTime = System.currentTimeMillis() + durationMillis

        prefs.edit()
            .putBoolean(KEY_IS_LOCKED, true)
            .putLong(KEY_LOCK_END_TIME, endTime)
            .putLong(KEY_LOCK_TOTAL_DURATION, durationMillis)
            .apply()

        // Start foreground service safely
        val serviceIntent = Intent(this, LockService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        showActiveLockScreen(endTime, durationMillis)
    }

    private fun stopFocusSession() {
        activeTimer?.cancel()
        prefs.edit()
            .putBoolean(KEY_IS_LOCKED, false)
            .putLong(KEY_LOCK_END_TIME, 0L)
            .apply()

        stopService(Intent(this, LockService::class.java))

        activeLockOverlay.animate()
            .alpha(0f)
            .setDuration(250)
            .withEndAction {
                activeLockOverlay.visibility = View.GONE
                activeLockOverlay.alpha = 1f
            }
            .start()
    }

    private fun checkAndSyncActiveSession() {
        val isLocked = prefs.getBoolean(KEY_IS_LOCKED, false)
        val endTime = prefs.getLong(KEY_LOCK_END_TIME, 0L)
        val totalDuration = prefs.getLong(KEY_LOCK_TOTAL_DURATION, 30 * 60 * 1000L)

        if (isLocked && endTime > System.currentTimeMillis()) {
            showActiveLockScreen(endTime, totalDuration)
        } else if (isLocked) {
            stopFocusSession()
        } else {
            activeLockOverlay.visibility = View.GONE
        }
    }

    private fun showActiveLockScreen(endTime: Long, totalDuration: Long) {
        if (activeLockOverlay.visibility != View.VISIBLE) {
            activeLockOverlay.alpha = 0f
            activeLockOverlay.visibility = View.VISIBLE
            activeLockOverlay.animate()
                .alpha(1f)
                .setDuration(280)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }

        activeTimer?.cancel()
        val remaining = endTime - System.currentTimeMillis()
        if (remaining <= 0) {
            stopFocusSession()
            return
        }

        activeTimer = object : CountDownTimer(remaining, 100) {
            override fun onTick(millisUntilFinished: Long) {
                val totalSeconds = millisUntilFinished / 1000
                val hours = totalSeconds / 3600
                val minutes = (totalSeconds % 3600) / 60
                val seconds = totalSeconds % 60

                timerDisplayText.text = if (hours > 0) {
                    String.format("%02d:%02d:%02d", hours, minutes, seconds)
                } else {
                    String.format("%02d:%02d", minutes, seconds)
                }

                val progress = (millisUntilFinished.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f)
                circularProgressView.setProgressSmooth(progress)
            }

            override fun onFinish() {
                stopFocusSession()
            }
        }.start()
    }

    // =========================================================================
    // PERMISSION HELPERS
    // =========================================================================

    private fun hasAllRequiredPermissions(): Boolean {
        return isAccessibilityEnabled() && Settings.canDrawOverlays(this)
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return enabledServices.any {
            it.resolveInfo.serviceInfo.packageName == packageName
        }
    }

    private fun updatePermissionBanner() {
        val accOk = isAccessibilityEnabled()
        val overlayOk = Settings.canDrawOverlays(this)

        if (accOk && overlayOk) {
            permissionBanner.visibility = View.GONE
        } else {
            permissionBanner.visibility = View.VISIBLE
            permissionStatusText.text = when {
                !accOk && !overlayOk -> "Enable Accessibility & Overlay to block apps"
                !accOk -> "Enable Accessibility Service to detect apps"
                else -> "Enable Display Over Other Apps permission"
            }
        }
    }

    private fun openMissingPermissions() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } else if (!isAccessibilityEnabled()) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    // =========================================================================
    // RECYCLERVIEW ADAPTER WITH ASYNC ICON LOADING & DIFFUTIL
    // =========================================================================

    inner class SmoothAppAdapter(
        private val onItemClick: (AppItem) -> Unit
    ) : ListAdapter<AppItem, SmoothAppAdapter.AppViewHolder>(AppDiffCallback()) {

        inner class AppViewHolder(val container: LinearLayout) : RecyclerView.ViewHolder(container) {
            val iconView: ImageView = container.getChildAt(0) as ImageView
            val nameText: TextView = (container.getChildAt(1) as LinearLayout).getChildAt(0) as TextView
            val pkgText: TextView = (container.getChildAt(1) as LinearLayout).getChildAt(1) as TextView
            val statusPill: TextView = container.getChildAt(2) as TextView
            var boundPackage: String = ""
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(8)
                }
            }

            val iconView = ImageView(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply {
                    marginEnd = dp(14)
                }
                scaleType = ImageView.ScaleType.FIT_CENTER
            }

            val textCol = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(10)
                }
            }

            val nameText = TextView(parent.context).apply {
                setTextColor(colorTextPrimary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
            }

            val pkgText = TextView(parent.context).apply {
                setTextColor(colorTextSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                maxLines = 1
            }

            textCol.addView(nameText)
            textCol.addView(pkgText)

            val statusPill = TextView(parent.context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(12), dp(6), dp(12), dp(6))
            }

            row.addView(iconView)
            row.addView(textCol)
            row.addView(statusPill)

            return AppViewHolder(row)
        }

        override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
            val item = getItem(position)
            holder.boundPackage = item.packageName
            holder.nameText.text = item.appName
            holder.pkgText.text = item.packageName

            if (item.isBlocked) {
                holder.container.background = createRoundedDrawable(
                    Color.parseColor("#1A1F33"),
                    dp(14).toFloat(),
                    dp(1),
                    colorAccent
                )
                holder.statusPill.text = "BLOCKED"
                holder.statusPill.setTextColor(Color.WHITE)
                holder.statusPill.background = createRoundedDrawable(colorAccent, dp(10).toFloat())
            } else {
                holder.container.background = createRoundedDrawable(
                    colorSurface,
                    dp(14).toFloat(),
                    dp(1),
                    colorBorder
                )
                holder.statusPill.text = "ALLOW"
                holder.statusPill.setTextColor(colorTextSecondary)
                holder.statusPill.background = createRoundedDrawable(colorSurfaceElevated, dp(10).toFloat())
            }

            // Load pre-scaled icon from LRU cache or decode asynchronously on background thread
            val cachedBitmap = iconCache.get(item.packageName)
            if (cachedBitmap != null) {
                holder.iconView.setImageBitmap(cachedBitmap)
            } else {
                holder.iconView.setImageDrawable(null)
                bgExecutor.execute {
                    try {
                        val rawDrawable = packageManager.getApplicationIcon(item.packageName)
                        val scaledBitmap = drawableToScaledBitmap(rawDrawable, dp(42))
                        iconCache.put(item.packageName, scaledBitmap)
                        mainHandler.post {
                            if (holder.boundPackage == item.packageName) {
                                holder.iconView.setImageBitmap(scaledBitmap)
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
            }

            holder.container.setOnClickListener {
                performTactileTap(it)
                onItemClick(item)
            }
        }
    }

    class AppDiffCallback : DiffUtil.ItemCallback<AppItem>() {
        override fun areItemsTheSame(oldItem: AppItem, newItem: AppItem): Boolean {
            return oldItem.packageName == newItem.packageName
        }

        override fun areContentsTheSame(oldItem: AppItem, newItem: AppItem): Boolean {
            return oldItem == newItem
        }
    }

    // =========================================================================
    // CUSTOM HARDWARE-ACCELERATED PROGRESS RING VIEW
    // =========================================================================

    inner class SmoothProgressRing(context: Context) : View(context) {
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(10).toFloat()
            color = colorSurfaceElevated
        }

        private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(10).toFloat()
            strokeCap = Paint.Cap.ROUND
            color = colorAccent
        }

        private val rectF = RectF()
        private var currentProgress = 1f

        fun setProgressSmooth(target: Float) {
            currentProgress = target
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val pad = dp(12).toFloat()
            rectF.set(pad, pad, width - pad, height - pad)
            canvas.drawArc(rectF, 0f, 360f, false, trackPaint)
            canvas.drawArc(rectF, -90f, 360f * currentProgress, false, progressPaint)
        }
    }

    // =========================================================================
    // UI STYLING & TACTILE FEEDBACK UTILITIES
    // =========================================================================

    private fun createPillToggle(
        label: String,
        initialState: Boolean,
        onChanged: (Boolean) -> Unit
    ): TextView {
        var isChecked = initialState
        return TextView(this).apply {
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(9), dp(10), dp(9))

            fun render() {
                text = if (isChecked) "✓ $label" else "○ $label"
                setTextColor(if (isChecked) colorSuccess else colorTextSecondary)
                background = createRoundedDrawable(
                    if (isChecked) Color.parseColor("#0D2820") else colorSurfaceElevated,
                    dp(10).toFloat(),
                    dp(1),
                    if (isChecked) colorSuccess else colorBorder
                )
            }
            render()

            setOnClickListener {
                performTactileTap(it)
                isChecked = !isChecked
                render()
                onChanged(isChecked)
            }
        }
    }

    private fun performTactileTap(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        view.animate()
            .scaleX(0.96f)
            .scaleY(0.96f)
            .setDuration(70)
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(110)
                    .setInterpolator(OvershootInterpolator(1.5f))
                    .start()
            }
            .start()
    }

    private fun drawableToScaledBitmap(drawable: Drawable, sizePx: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(canvas)
        return bitmap
    }

    private fun createRoundedDrawable(
        fillColor: Int,
        radius: Float,
        strokeWidth: Int = 0,
        strokeColor: Int = Color.TRANSPARENT
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fillColor)
            cornerRadius = radius
            if (strokeWidth > 0) {
                setStroke(strokeWidth, strokeColor)
            }
        }
    }

    private fun createRippleDrawable(
        normalColor: Int,
        rippleColor: Int,
        radius: Float
    ): RippleDrawable {
        val content = createRoundedDrawable(normalColor, radius)
        val mask = createRoundedDrawable(Color.WHITE, radius)
        return RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask)
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }
}
