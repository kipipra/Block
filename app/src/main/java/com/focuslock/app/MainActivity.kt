package com.focuslock.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.DateFormat
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

private const val MATCH = -1
private const val WRAP = -2

private object C {
    val BG: Int = Color.parseColor("#03070A")
    val SURFACE: Int = Color.parseColor("#0A131B")
    val SURFACE_HI: Int = Color.parseColor("#0F1C27")
    val SURFACE2: Int = Color.parseColor("#14252F")
    val LINE: Int = Color.parseColor("#1C3441")
    val FG: Int = Color.parseColor("#E4F7FA")
    val MUTED: Int = Color.parseColor("#8FA9B3")
    val ACCENT: Int = Color.parseColor("#3CD8EC")
    val ACCENT_HI: Int = Color.parseColor("#8CF1FF")
    val INK: Int = Color.parseColor("#021317")
    val DANGER: Int = Color.parseColor("#FF7A6B")
    val OK: Int = Color.parseColor("#6FE3B5")
}

class MainActivity : Activity() {

    companion object {
        const val MAX_MIN = 720          // 12 h
        const val STEP = 5               // slider moves in 5 minute steps
        val PRESETS = intArrayOf(15, 25, 45, 60, 120)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val iconCache = ConcurrentHashMap<String, Drawable>()
    private lateinit var prefs: SharedPreferences

    private var minutes = 25
    private var syncing = false
    private var animatingSlider = false
    private var sliderAnim: ValueAnimator? = null
    private var selectedPreset = -2
    private var shownLocked: Boolean? = null
    private var resumed = false

    // last texts, so a view is only touched when its text really changes
    private var lastDur = ""
    private var lastEnd = ""
    private var lastHelper = ""
    private var lastBtn = ""

    private lateinit var root: ScrollView
    private lateinit var col: LinearLayout
    private lateinit var permPill: TextView
    private lateinit var permCard: LinearLayout
    private lateinit var setupBox: LinearLayout
    private lateinit var lockedBox: LinearLayout
    private lateinit var ring: RingView
    private lateinit var lockedUntil: TextView
    private lateinit var durText: TextView
    private lateinit var endText: TextView
    private lateinit var helper: TextView
    private lateinit var seek: SeekBar
    private lateinit var hoursIn: EditText
    private lateinit var minsIn: EditText
    private lateinit var lockBtn: Button
    private lateinit var lastBlockedView: TextView
    private val slots = ArrayList<ImageView>()
    private val presetBtns = ArrayList<Button>()

    private val ticker = object : Runnable {
        override fun run() {
            val locked = LockAccessibilityService.isLocked()
            syncState(locked)
            if (locked) {
                val left = LockAccessibilityService.lockEndTime - System.currentTimeMillis()
                handler.postDelayed(this, left.coerceIn(50L, 1000L))
            } else {
                refreshEndEstimate()
                handler.postDelayed(this, 15_000L)
            }
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LockAccessibilityService.init(this)
        prefs = getSharedPreferences("focuslock_ui", Context.MODE_PRIVATE)
        minutes = prefs.getInt("minutes", 25).coerceIn(1, MAX_MIN)
        window.statusBarColor = C.BG
        window.navigationBarColor = C.BG
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContentView(buildUi())
        applyMinutes(minutes, fields = true, slider = true)
        refreshSlots()
        val locked = LockAccessibilityService.isLocked()
        syncState(locked)
        if (savedInstanceState == null && !locked) playEntrance()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        refreshPermission()
        refreshLastBlocked()
        refreshSlots()
        updateBreathing()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        updateBreathing()
        handler.removeCallbacks(ticker)
    }

    override fun onDestroy() {
        sliderAnim?.removeAllListeners()
        sliderAnim?.cancel()
        handler.removeCallbacksAndMessages(null)
        io.shutdownNow()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ UI

    private fun buildUi(): View {
        root = ScrollView(this)
        // deep dark background with a soft cyan glow at the top, like the logo
        val glowBg = GradientDrawable()
        glowBg.setGradientType(GradientDrawable.RADIAL_GRADIENT)
        glowBg.setGradientCenter(0.5f, 0.0f)
        glowBg.setGradientRadius(dp(480).toFloat())
        glowBg.setColors(intArrayOf(Color.argb(64, 60, 216, 236), Color.argb(0, 60, 216, 236)))
        root.background = android.graphics.drawable.LayerDrawable(arrayOf<Drawable>(ColorDrawable(C.BG), glowBg))
        root.isFillViewport = true
        root.isVerticalScrollBarEnabled = false
        root.isFocusableInTouchMode = true
        root.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS

        col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(20), dp(36), dp(20), dp(40))
        root.addView(col, ViewGroup.LayoutParams(MATCH, WRAP))

        // header: title and a small permission pill
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        val logo = ImageView(this)
        logo.setImageResource(R.drawable.logo)
        logo.scaleType = ImageView.ScaleType.FIT_CENTER
        header.addView(logo, LinearLayout.LayoutParams(dp(56), dp(56)))
        val titles = LinearLayout(this)
        titles.orientation = LinearLayout.VERTICAL
        val nameText = text("FORTIS", 26f, C.FG, bold = true)
        nameText.letterSpacing = 0.16f
        val tagText = text("FOCUS. PAUSE. ACHIEVE.", 10.5f, C.ACCENT)
        tagText.letterSpacing = 0.18f
        titles.addView(nameText)
        titles.addView(tagText)
        val titlesLp = LinearLayout.LayoutParams(0, WRAP, 1f)
        titlesLp.leftMargin = dp(8)
        header.addView(titles, titlesLp)
        permPill = text("", 12f, C.OK, bold = true)
        permPill.setPadding(dp(12), dp(7), dp(12), dp(7))
        permPill.setOnClickListener {
            if (!isAccessibilityEnabled()) startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        permPill.pressable()
        header.addView(permPill, LinearLayout.LayoutParams(WRAP, WRAP))
        col.addView(header, lp(0))
        col.addView(text("Pick a time. When it starts, the phone stays locked until it ends.", 14f, C.MUTED), lp(6))

        // permission card, only shown while the permission is off
        permCard = card()
        permCard.visibility = View.GONE
        permCard.addView(text("Lock permission is off", 16f, C.DANGER, bold = true), lp(0))
        permCard.addView(
            text(
                "Open Accessibility settings, tap FORTIS and switch it on. If the switch is greyed out: Settings, Apps, FORTIS, three dots at the top right, Allow restricted settings.",
                13f, C.MUTED
            ), lp(8)
        )
        val permBtn = primaryButton("Turn on lock permission")
        permBtn.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        permCard.addView(permBtn, lp(14))
        col.addView(permCard, lp(20))

        // ---- locked state
        lockedBox = card()
        lockedBox.gravity = Gravity.CENTER_HORIZONTAL
        lockedBox.setPadding(dp(16), dp(28), dp(16), dp(28))
        lockedBox.visibility = View.GONE
        ring = RingView(this)
        val lockLogo = ImageView(this)
        lockLogo.setImageResource(R.drawable.logo)
        lockLogo.scaleType = ImageView.ScaleType.FIT_CENTER
        lockedBox.addView(lockLogo, LinearLayout.LayoutParams(dp(76), dp(76)))
        lockedBox.addView(ring, LinearLayout.LayoutParams(dp(250), dp(250)))
        lockedUntil = text("", 16f, C.FG, bold = true, center = true)
        lockedBox.addView(lockedUntil, lp(14))
        lockedBox.addView(text("This lock cannot be cancelled. Come back when the timer ends.", 13f, C.MUTED, center = true), lp(10))
        col.addView(lockedBox, lp(24))

        // ---- setup state
        setupBox = LinearLayout(this)
        setupBox.orientation = LinearLayout.VERTICAL
        col.addView(setupBox, lp(0))

        // hero card: duration, slider, presets
        val hero = card()
        hero.addView(label("LOCK FOR"), lp(0))
        durText = text("25 min", 52f, C.ACCENT, bold = true)
        durText.post {
            durText.pivotX = 0f
            durText.pivotY = durText.height / 2f
        }
        hero.addView(durText, lp(4))
        endText = text("", 14f, C.MUTED)
        hero.addView(endText, lp(2))

        seek = SeekBar(this)
        seek.max = MAX_MIN / STEP - 1
        seek.setPadding(dp(14), dp(10), dp(14), dp(10))
        seek.progressTintList = ColorStateList.valueOf(C.ACCENT)
        seek.thumbTintList = ColorStateList.valueOf(C.ACCENT)
        seek.progressBackgroundTintList = ColorStateList.valueOf(C.SURFACE2)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser && !animatingSlider) return
                val m = (p + 1) * STEP
                if (m == minutes) return
                minutes = m
                renderReadouts()
                syncFields()
                if (fromUser) s?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }

            override fun onStartTrackingTouch(s: SeekBar?) {
                sliderAnim?.removeAllListeners()
                sliderAnim?.cancel()
                animatingSlider = false
                dropFocus()
            }

            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        hero.addView(seek, lp(12))

        val scale = LinearLayout(this)
        scale.orientation = LinearLayout.HORIZONTAL
        scale.setPadding(dp(4), 0, dp(4), 0)
        scale.addView(text("5 min", 12f, C.MUTED, mono = true), LinearLayout.LayoutParams(0, WRAP, 1f))
        val maxLabel = text("12 h", 12f, C.MUTED, mono = true)
        maxLabel.gravity = Gravity.END
        scale.addView(maxLabel, LinearLayout.LayoutParams(0, WRAP, 1f))
        hero.addView(scale, lp(0))

        val presets = LinearLayout(this)
        presets.orientation = LinearLayout.HORIZONTAL
        for (m in PRESETS) {
            val b = Button(this)
            b.text = fmtDuration(m)
            b.isAllCaps = false
            b.textSize = 13f
            b.typeface = Typeface.DEFAULT_BOLD
            b.minHeight = dp(46)
            b.minimumHeight = dp(46)
            b.minWidth = 0
            b.minimumWidth = 0
            b.setPadding(0, 0, 0, 0)
            b.stateListAnimator = null
            styleChip(b, false)
            b.pressable()
            b.setOnClickListener { animateTo(m) }
            val p = LinearLayout.LayoutParams(0, WRAP, 1f)
            p.leftMargin = dp(3)
            p.rightMargin = dp(3)
            presets.addView(b, p)
            presetBtns.add(b)
        }
        hero.addView(presets, lp(16))
        setupBox.addView(hero, lp(20))

        // exact time card
        val tc = card()
        tc.addView(label("OR TYPE AN EXACT TIME"), lp(0))
        val custom = LinearLayout(this)
        custom.orientation = LinearLayout.HORIZONTAL
        custom.gravity = Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL
        hoursIn = numberField(2)
        minsIn = numberField(3)
        custom.addView(hoursIn, LinearLayout.LayoutParams(dp(88), dp(64)))
        custom.addView(unit("h"), LinearLayout.LayoutParams(WRAP, WRAP))
        custom.addView(View(this), LinearLayout.LayoutParams(dp(16), 1))
        custom.addView(minsIn, LinearLayout.LayoutParams(dp(88), dp(64)))
        custom.addView(unit("min"), LinearLayout.LayoutParams(WRAP, WRAP))
        tc.addView(custom, lp(12))
        helper = text("", 13f, C.MUTED, center = true)
        tc.addView(helper, lp(10))
        setupBox.addView(tc, lp(14))

        // allowed apps card: six slots
        val ac = card()
        ac.addView(label("APPS ALLOWED WHILE LOCKED"), lp(0))
        val slotRow = LinearLayout(this)
        slotRow.orientation = LinearLayout.HORIZONTAL
        for (i in 0 until LockAccessibilityService.MAX_ALLOWED) {
            val iv = ImageView(this)
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            iv.pressable()
            iv.setOnClickListener {
                dropFocus()
                showAppPicker()
            }
            val p = LinearLayout.LayoutParams(0, dp(52), 1f)
            p.leftMargin = dp(3)
            p.rightMargin = dp(3)
            slotRow.addView(iv, p)
            slots.add(iv)
        }
        ac.addView(slotRow, lp(14))
        ac.addView(
            text("Tap to choose up to 6. Phone calls always work. You can only change these before the lock starts.", 12.5f, C.MUTED),
            lp(12)
        )
        setupBox.addView(ac, lp(14))

        lastBlockedView = text("", 11.5f, C.MUTED)
        setupBox.addView(lastBlockedView, lp(16))

        lockBtn = primaryButton("Lock")
        lockBtn.setOnClickListener { onLockClicked() }
        setupBox.addView(lockBtn, lp(20))

        return root
    }

    private fun card(): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(18), dp(18), dp(18), dp(18))
        val bg = gradientDrawable(C.SURFACE_HI, C.SURFACE, dp(22))
        bg.setStroke(dp(1), C.LINE)
        c.background = bg
        return c
    }

    private fun numberField(maxLen: Int): EditText {
        val e = EditText(this)
        e.inputType = InputType.TYPE_CLASS_NUMBER
        e.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(maxLen))
        e.setSingleLine(true)
        e.gravity = Gravity.CENTER
        e.textSize = 28f
        e.typeface = Typeface.MONOSPACE
        e.setTextColor(C.ACCENT)
        e.setHintTextColor(C.MUTED)
        e.hint = "0"
        e.setSelectAllOnFocus(true)
        e.imeOptions = EditorInfo.IME_ACTION_DONE
        val bg = rounded(C.SURFACE2, dp(14), C.LINE, dp(1))
        val bgFocus = rounded(C.SURFACE2, dp(14), C.ACCENT, dp(2))
        e.background = bg
        e.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!syncing) onFieldsEdited()
            }
        })
        e.setOnFocusChangeListener { _, has ->
            e.background = if (has) bgFocus else bg
            if (!has && !syncing) applyMinutes(minutes, fields = true, slider = true)
        }
        e.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                dropFocus()
                true
            } else {
                false
            }
        }
        return e
    }

    private fun unit(label: String): TextView {
        val t = text(label, 18f, C.MUTED, bold = true)
        t.setPadding(dp(8), 0, 0, 0)
        return t
    }

    private fun playEntrance() {
        val targets = ArrayList<View>()
        for (i in 0 until col.childCount) {
            val v = col.getChildAt(i)
            if (v === setupBox) {
                for (j in 0 until setupBox.childCount) targets.add(setupBox.getChildAt(j))
            } else if (v.visibility == View.VISIBLE) {
                targets.add(v)
            }
        }
        for ((i, v) in targets.withIndex()) {
            v.alpha = 0f
            v.translationY = dp(18).toFloat()
            v.animate()
                .alpha(1f).translationY(0f)
                .setStartDelay(60L + i * 55L)
                .setDuration(380L)
                .setInterpolator(DecelerateInterpolator(1.8f))
                .start()
        }
    }

    // ------------------------------------------------------------------ time state

    /** Updates the readout, slider, presets, fields and the lock button from one number of minutes. */
    private fun applyMinutes(raw: Int, fields: Boolean, slider: Boolean) {
        syncing = true
        minutes = raw.coerceIn(0, MAX_MIN)
        renderReadouts(raw)
        if (slider) {
            seek.progress = ((minutes + STEP / 2) / STEP - 1).coerceIn(0, seek.max)
        }
        if (fields) syncFields()
        syncing = false
    }

    private fun syncFields() {
        val wasSyncing = syncing
        syncing = true
        setFieldText(hoursIn, (minutes / 60).toString())
        setFieldText(minsIn, (minutes % 60).toString())
        syncing = wasSyncing
    }

    private fun setFieldText(e: EditText, s: String) {
        if (e.text.toString() != s) e.setText(s)
    }

    /** Only touches a view when its text actually changed, so dragging the slider stays cheap. */
    private fun renderReadouts(raw: Int = minutes) {
        val d = if (minutes == 0) "0 min" else fmtDuration(minutes)
        if (d != lastDur) {
            lastDur = d
            durText.text = d
        }
        refreshEndEstimate()

        val idx = PRESETS.indexOf(minutes)
        if (idx != selectedPreset) {
            if (selectedPreset >= 0) styleChip(presetBtns[selectedPreset], false)
            if (idx >= 0) styleChip(presetBtns[idx], true)
            selectedPreset = idx
        }

        val msg: String
        val msgColor: Int
        when {
            raw > MAX_MIN -> {
                msg = "The longest lock is 12 h, so that is what will be used."
                msgColor = C.DANGER
            }
            minutes == 0 -> {
                msg = "Enter at least 1 minute."
                msgColor = C.DANGER
            }
            else -> {
                msg = "Hours and minutes add up. 90 min is the same as 1 h 30 min."
                msgColor = C.MUTED
            }
        }
        if (msg != lastHelper) {
            lastHelper = msg
            helper.text = msg
            helper.setTextColor(msgColor)
        }

        val ok = minutes > 0
        val bt = if (ok) "Lock for " + fmtDuration(minutes) else "Set a time"
        if (bt != lastBtn) {
            lastBtn = bt
            lockBtn.text = bt
        }
        lockBtn.isEnabled = ok
        lockBtn.alpha = if (ok) 1f else 0.4f
    }

    /** Glides the slider to a preset while the number counts along with it. */
    private fun animateTo(target: Int) {
        dropFocus()
        sliderAnim?.removeAllListeners()
        sliderAnim?.cancel()
        val to = (target / STEP - 1).coerceIn(0, seek.max)
        val from = seek.progress
        popDuration()
        if (from == to) {
            applyMinutes(target, fields = true, slider = true)
            return
        }
        animatingSlider = true
        val a = ValueAnimator.ofInt(from, to)
        a.duration = 300L
        a.interpolator = DecelerateInterpolator(1.6f)
        a.addUpdateListener { seek.progress = it.animatedValue as Int }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                animatingSlider = false
                applyMinutes(target, fields = true, slider = true)
            }
        })
        sliderAnim = a
        a.start()
    }

    private fun popDuration() {
        durText.animate().cancel()
        durText.animate().scaleX(1.06f).scaleY(1.06f).setDuration(110L).withEndAction {
            durText.animate().scaleX(1f).scaleY(1f).setDuration(170L).start()
        }.start()
    }

    private fun refreshEndEstimate() {
        val s = if (minutes <= 0) "" else "Unlocks at about " + whenLabel(System.currentTimeMillis() + minutes * 60_000L)
        if (s != lastEnd) {
            lastEnd = s
            endText.text = s
        }
    }

    /** "10:15 AM, today" for a time stamp. */
    private fun whenLabel(end: Long): String {
        fun midnight(t: Long): Long = java.util.Calendar.getInstance().apply {
            timeInMillis = t
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val days = Math.round((midnight(end) - midnight(System.currentTimeMillis())) / 86_400_000.0).toInt()
        val day = when (days) {
            0 -> "today"
            1 -> "tomorrow"
            else -> DateFormat.getMediumDateFormat(this).format(Date(end))
        }
        return clock(end) + ", " + day
    }

    private fun onFieldsEdited() {
        val h = hoursIn.text.toString().toIntOrNull() ?: 0
        val m = minsIn.text.toString().toIntOrNull() ?: 0
        applyMinutes(h * 60 + m, fields = false, slider = true)
    }

    private fun styleChip(b: Button, selected: Boolean) {
        b.background = withRipple(roundedDrawable(if (selected) C.ACCENT else C.SURFACE2, dp(14)), dp(14))
        b.setTextColor(if (selected) C.INK else C.FG)
    }

    private fun dropFocus() {
        val f = currentFocus
        if (f is EditText) {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(f.windowToken, 0)
        }
        root.requestFocus()
        applyMinutes(minutes, fields = true, slider = true)
    }

    // ------------------------------------------------------------------ allowed apps

    private class AppItem(val pkg: String, val label: String, val icon: Drawable)

    private fun launchableApps(): List<AppItem> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { AppItem(it.activityInfo.packageName, it.loadLabel(pm).toString(), it.loadIcon(pm)) }
            .filter { it.pkg != packageName }
            .distinctBy { it.pkg }
    }

    private fun iconFor(pkg: String): Drawable? {
        val d = iconCache[pkg] ?: return null
        return d.constantState?.newDrawable() ?: d
    }

    private fun refreshSlots() {
        val pkgs = LockAccessibilityService.allowedApps
        for (i in slots.indices) {
            val iv = slots[i]
            val pkg = pkgs.getOrNull(i)
            if (pkg == null) {
                val g = roundedDrawable(Color.TRANSPARENT, dp(14), C.LINE, dp(1))
                g.setStroke(dp(1), C.LINE, dp(5).toFloat(), dp(4).toFloat())
                iv.background = g
                iv.setPadding(dp(16), dp(16), dp(16), dp(16))
                iv.setImageResource(android.R.drawable.ic_input_add)
                iv.setColorFilter(C.MUTED)
                iv.alpha = 0.7f
            } else {
                iv.background = withRipple(roundedDrawable(C.SURFACE2, dp(14)), dp(14))
                iv.setPadding(dp(8), dp(8), dp(8), dp(8))
                iv.clearColorFilter()
                iv.alpha = 1f
                val d = iconFor(pkg)
                if (d != null) {
                    iv.setImageDrawable(d)
                } else {
                    iv.setImageDrawable(null)
                    loadIcon(pkg)
                }
            }
        }
    }

    private fun loadIcon(pkg: String) {
        if (io.isShutdown) return
        io.execute {
            val d = try {
                packageManager.getApplicationIcon(pkg)
            } catch (_: Exception) {
                null
            }
            if (d != null) {
                iconCache[pkg] = d
                runOnUiThread { if (!isFinishing) refreshSlots() }
            }
        }
    }

    private fun refreshLastBlocked() {
        val lb = LockAccessibilityService.lastBlocked
        lastBlockedView.text = "Last blocked: " + (if (lb.isEmpty()) "nothing yet" else lb)
    }

    // ------------------------------------------------------------------ bottom sheets

    private fun newSheet(): Pair<Dialog, LinearLayout> {
        val dlg = Dialog(this)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val sheet = LinearLayout(this)
        sheet.orientation = LinearLayout.VERTICAL
        sheet.setPadding(dp(20), dp(10), dp(20), dp(20))
        val r = dp(26).toFloat()
        val bg = GradientDrawable()
        bg.setColor(C.SURFACE)
        bg.cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        sheet.background = bg
        val handle = View(this)
        handle.background = rounded(C.LINE, dp(3))
        val hp = LinearLayout.LayoutParams(dp(40), dp(5))
        hp.gravity = Gravity.CENTER_HORIZONTAL
        hp.bottomMargin = dp(14)
        sheet.addView(handle, hp)
        dlg.setContentView(sheet)
        dlg.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setDimAmount(0.6f)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        return Pair(dlg, sheet)
    }

    private fun showSheet(dlg: Dialog, sheet: View) {
        sheet.translationY = dp(700).toFloat()
        dlg.show()
        sheet.animate().translationY(0f).setDuration(280L).setInterpolator(DecelerateInterpolator(2f)).start()
    }

    private fun closeSheet(dlg: Dialog, sheet: View) {
        sheet.animate().translationY(sheet.height.toFloat()).setDuration(180L)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { if (dlg.isShowing) dlg.dismiss() }
            .start()
    }

    private inner class PickerRow {
        val root = LinearLayout(this@MainActivity)
        val icon = ImageView(this@MainActivity)
        val label = TextView(this@MainActivity)
        val check = TextView(this@MainActivity)
        private val bgOn = withRipple(roundedDrawable(Color.argb(34, 60, 216, 236), dp(14)), dp(14))
        private val bgOff = withRipple(roundedDrawable(Color.TRANSPARENT, dp(14)), dp(14))
        private val checkOn = roundedDrawable(C.ACCENT, dp(13))
        private val checkOff = roundedDrawable(Color.TRANSPARENT, dp(13), C.LINE, dp(2))

        init {
            root.orientation = LinearLayout.HORIZONTAL
            root.gravity = Gravity.CENTER_VERTICAL
            root.setPadding(dp(8), dp(8), dp(8), dp(8))
            root.addView(icon, LinearLayout.LayoutParams(dp(44), dp(44)))
            label.setTextColor(C.FG)
            label.textSize = 16f
            label.maxLines = 1
            label.ellipsize = TextUtils.TruncateAt.END
            val lpLabel = LinearLayout.LayoutParams(0, WRAP, 1f)
            lpLabel.leftMargin = dp(14)
            lpLabel.rightMargin = dp(10)
            root.addView(label, lpLabel)
            check.gravity = Gravity.CENTER
            check.textSize = 14f
            check.typeface = Typeface.DEFAULT_BOLD
            root.addView(check, LinearLayout.LayoutParams(dp(26), dp(26)))
            root.tag = this
        }

        fun bind(a: AppItem, selected: Boolean) {
            icon.setImageDrawable(a.icon.constantState?.newDrawable() ?: a.icon)
            label.text = a.label
            root.background = if (selected) bgOn else bgOff
            check.background = if (selected) checkOn else checkOff
            check.text = if (selected) "\u2713" else ""
            check.setTextColor(C.INK)
            label.setTextColor(if (selected) C.ACCENT_HI else C.FG)
        }
    }

    private fun showAppPicker() {
        if (LockAccessibilityService.isLocked()) return
        val (dlg, sheet) = newSheet()
        val chosen = LinkedHashSet<String>(LockAccessibilityService.allowedApps)
        val max = LockAccessibilityService.MAX_ALLOWED

        val titleRow = LinearLayout(this)
        titleRow.orientation = LinearLayout.HORIZONTAL
        titleRow.gravity = Gravity.CENTER_VERTICAL
        titleRow.addView(text("Allowed apps", 22f, C.FG, bold = true), LinearLayout.LayoutParams(0, WRAP, 1f))
        val count = text("", 14f, C.ACCENT, bold = true)
        titleRow.addView(count, LinearLayout.LayoutParams(WRAP, WRAP))
        sheet.addView(titleRow, lp(0))
        fun updateCount() {
            count.text = chosen.size.toString() + " of " + max
        }
        updateCount()
        sheet.addView(text("These stay usable while the phone is locked.", 13f, C.MUTED), lp(4))

        val search = EditText(this)
        search.hint = "Search apps"
        search.setSingleLine(true)
        search.textSize = 15f
        search.setTextColor(C.FG)
        search.setHintTextColor(C.MUTED)
        search.setPadding(dp(16), 0, dp(16), 0)
        search.background = rounded(C.SURFACE2, dp(14))
        search.imeOptions = EditorInfo.IME_ACTION_DONE
        val sp = LinearLayout.LayoutParams(MATCH, dp(46))
        sp.topMargin = dp(14)
        sheet.addView(search, sp)

        var all: List<AppItem> = emptyList()
        var shown: List<AppItem> = emptyList()

        val adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(i: Int): Any = shown[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, convert: View?, parent: ViewGroup?): View {
                val row = (convert?.tag as? PickerRow) ?: PickerRow()
                val a = shown[i]
                row.bind(a, a.pkg in chosen)
                return row.root
            }
        }

        val list = ListView(this)
        list.divider = null
        list.selector = ColorDrawable(Color.TRANSPARENT)
        list.isVerticalScrollBarEnabled = false
        list.adapter = adapter
        val loading = text("Loading apps...", 14f, C.MUTED, center = true)
        val frame = FrameLayout(this)
        frame.addView(list, FrameLayout.LayoutParams(MATCH, MATCH))
        frame.addView(loading, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER))
        val listH = (resources.displayMetrics.heightPixels * 0.46f).toInt()
        val fp = LinearLayout.LayoutParams(MATCH, listH)
        fp.topMargin = dp(10)
        sheet.addView(frame, fp)

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s.toString().trim().lowercase(Locale.ROOT)
                shown = if (q.isEmpty()) all else all.filter { it.label.lowercase(Locale.ROOT).contains(q) }
                adapter.notifyDataSetChanged()
            }
        })

        list.setOnItemClickListener { _, _, pos, _ ->
            val a = shown.getOrNull(pos) ?: return@setOnItemClickListener
            if (a.pkg in chosen) {
                chosen.remove(a.pkg)
            } else if (chosen.size >= max) {
                Toast.makeText(this, "You can pick up to $max apps.", Toast.LENGTH_SHORT).show()
                return@setOnItemClickListener
            } else {
                chosen.add(a.pkg)
                iconCache[a.pkg] = a.icon
            }
            list.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            // Saved on every tap, so closing the sheet any way never loses the choice.
            LockAccessibilityService.allowedApps = chosen.toList()
            updateCount()
            refreshSlots()
            adapter.notifyDataSetChanged()
        }

        val done = primaryButton("Done")
        done.setOnClickListener { closeSheet(dlg, sheet) }
        sheet.addView(done, lp(14))

        dlg.setOnDismissListener { refreshSlots() }
        showSheet(dlg, sheet)

        if (io.isShutdown) return
        io.execute {
            val items = launchableApps()
            runOnUiThread {
                if (!dlg.isShowing) return@runOnUiThread
                for (a in items) iconCache[a.pkg] = a.icon
                all = items.sortedWith(
                    compareByDescending<AppItem> { it.pkg in chosen }.thenBy { it.label.lowercase(Locale.ROOT) }
                )
                shown = all
                loading.visibility = View.GONE
                adapter.notifyDataSetChanged()
            }
        }
    }

    // ------------------------------------------------------------------ locking

    private fun onLockClicked() {
        dropFocus()
        if (LockAccessibilityService.isLocked()) return
        if (!isAccessibilityEnabled()) {
            Toast.makeText(this, R.string.enable_accessibility, Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        if (minutes <= 0) return
        showConfirm()
    }

    private fun showConfirm() {
        val (dlg, sheet) = newSheet()
        val end = System.currentTimeMillis() + minutes * 60_000L
        sheet.addView(text("Lock for " + fmtDuration(minutes) + "?", 22f, C.FG, bold = true), lp(0))
        sheet.addView(
            text("Your phone will stay locked until " + whenLabel(end) + ". It cannot be cancelled once it starts.", 14f, C.MUTED),
            lp(8)
        )
        val go = primaryButton("Lock now")
        go.setOnClickListener {
            go.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            dlg.dismiss()
            startLock()
        }
        val cancel = secondaryButton("Cancel")
        cancel.setOnClickListener { closeSheet(dlg, sheet) }
        sheet.addView(go, lp(20))
        sheet.addView(cancel, lp(10))
        showSheet(dlg, sheet)
    }

    private fun startLock() {
        if (LockAccessibilityService.isLocked() || minutes <= 0) return
        val now = System.currentTimeMillis()
        prefs.edit().putInt("minutes", minutes).putLong("lockStart", now).apply()
        LockAccessibilityService.lockEndTime = now + minutes * 60_000L
        startForegroundService(Intent(this, LockService::class.java))
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val target = ComponentName(this, LockAccessibilityService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == target }
    }

    private fun refreshPermission() {
        val on = isAccessibilityEnabled()
        permPill.text = if (on) "\u25CF  Lock on" else "\u25CF  Lock off"
        permPill.setTextColor(if (on) C.OK else C.DANGER)
        permPill.background = rounded(
            if (on) Color.argb(34, 111, 199, 156) else Color.argb(34, 239, 130, 114),
            dp(20)
        )
        permCard.visibility = if (!on && shownLocked != true) View.VISIBLE else View.GONE
    }

    private fun lockStartFor(end: Long): Long {
        val s = prefs.getLong("lockStart", 0L)
        val total = end - s
        if (s > 0L && total > 0L && total <= MAX_MIN * 60_000L + 60_000L) return s
        val now = System.currentTimeMillis()
        prefs.edit().putLong("lockStart", now).apply()
        return now
    }

    private fun updateBreathing() {
        ring.setBreathing(resumed && shownLocked == true)
    }

    /** Switches between the setup screen and the locked screen with a short cross-fade. */
    private fun syncState(locked: Boolean) {
        if (shownLocked == locked) return
        val first = shownLocked == null
        shownLocked = locked
        if (locked) {
            val end = LockAccessibilityService.lockEndTime
            ring.startMs = lockStartFor(end)
            ring.endMs = end
            lockedUntil.text = "Unlocks at " + whenLabel(end)
        }
        refreshPermission()
        updateBreathing()
        val show: View = if (locked) lockedBox else setupBox
        val hide: View = if (locked) setupBox else lockedBox
        if (first) {
            hide.visibility = View.GONE
            show.visibility = View.VISIBLE
            return
        }
        hide.animate().cancel()
        show.animate().cancel()
        hide.animate().alpha(0f).setDuration(140L).withEndAction {
            hide.visibility = View.GONE
            hide.alpha = 1f
            root.scrollTo(0, 0)
            show.alpha = 0f
            show.translationY = dp(12).toFloat()
            show.visibility = View.VISIBLE
            show.animate().alpha(1f).translationY(0f).setDuration(260L)
                .setInterpolator(DecelerateInterpolator(1.6f)).start()
        }.start()
    }

    // ------------------------------------------------------------------ small helpers

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun clock(ts: Long): String = DateFormat.getTimeFormat(this).format(Date(ts))

    private fun lp(top: Int): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(MATCH, WRAP)
        p.topMargin = dp(top)
        return p
    }

    private fun rounded(fill: Int, radius: Int, stroke: Int = 0, strokePx: Int = 0): GradientDrawable =
        roundedDrawable(fill, radius, stroke, strokePx)

    private fun label(s: String): TextView {
        val t = text(s, 11.5f, C.MUTED, bold = true)
        t.letterSpacing = 0.12f
        return t
    }

    private fun text(
        s: String,
        sp: Float,
        color: Int,
        bold: Boolean = false,
        mono: Boolean = false,
        center: Boolean = false
    ): TextView {
        val t = TextView(this)
        t.text = s
        t.textSize = sp
        t.setTextColor(color)
        if (mono) t.typeface = Typeface.MONOSPACE else if (bold) t.typeface = Typeface.DEFAULT_BOLD
        if (center) t.gravity = Gravity.CENTER
        return t
    }

    private fun primaryButton(label: String): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.setTextColor(C.INK)
        b.textSize = 17f
        b.typeface = Typeface.DEFAULT_BOLD
        b.minHeight = dp(58)
        b.minimumHeight = dp(58)
        b.stateListAnimator = null
        b.background = withRipple(gradientDrawable(C.ACCENT_HI, C.ACCENT, dp(18)), dp(18), Color.argb(50, 0, 0, 0))
        b.pressable()
        return b
    }

    private fun secondaryButton(label: String): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.setTextColor(C.FG)
        b.textSize = 16f
        b.minHeight = dp(52)
        b.minimumHeight = dp(52)
        b.stateListAnimator = null
        b.background = withRipple(roundedDrawable(C.SURFACE2, dp(18)), dp(18))
        b.pressable()
        return b
    }
}

/** The countdown ring on the locked screen. It reads the clock itself every frame, so it never looks choppy. */
private class RingView(ctx: Context) : View(ctx) {
    var startMs = 0L
    var endMs = 0L

    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG)
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val big = Paint(Paint.ANTI_ALIAS_FLAG)
    private val small = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oval = RectF()
    private var phase = 0f
    private var anim: ValueAnimator? = null
    private var lastSec = -1L
    private var digits = ""

    init {
        track.style = Paint.Style.STROKE
        track.color = C.SURFACE2
        glow.style = Paint.Style.STROKE
        glow.strokeCap = Paint.Cap.ROUND
        glow.color = Color.argb(48, 60, 216, 236)
        arc.style = Paint.Style.STROKE
        arc.strokeCap = Paint.Cap.ROUND
        arc.color = C.ACCENT
        halo.style = Paint.Style.STROKE
        halo.color = C.ACCENT
        big.color = C.FG
        big.textAlign = Paint.Align.CENTER
        big.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        small.color = C.MUTED
        small.textAlign = Paint.Align.CENTER
    }

    /** A slow pulse around the ring while the lock screen is visible. */
    fun setBreathing(on: Boolean) {
        if (on && anim == null) {
            val a = ValueAnimator.ofFloat(0f, 1f)
            a.duration = 2600L
            a.repeatCount = ValueAnimator.INFINITE
            a.repeatMode = ValueAnimator.REVERSE
            a.interpolator = AccelerateDecelerateInterpolator()
            a.addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            anim = a
            a.start()
        } else if (!on && anim != null) {
            anim?.cancel()
            anim = null
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        anim = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val stroke = 10f * d
        val r = minOf(w, h) / 2f - 22f * d
        track.strokeWidth = stroke
        arc.strokeWidth = stroke
        glow.strokeWidth = stroke * 2.2f
        oval.set(cx - r, cy - r, cx + r, cy + r)

        halo.strokeWidth = 1.5f * d
        halo.alpha = (30 + 60 * phase).toInt()
        canvas.drawCircle(cx, cy, r + 14f * d + 4f * d * phase, halo)
        canvas.drawCircle(cx, cy, r, track)

        val now = System.currentTimeMillis()
        val total = (endMs - startMs).coerceAtLeast(1L)
        val left = (endMs - now).coerceIn(0L, total)
        val sweep = 360f * left / total
        if (sweep > 0.5f) {
            canvas.drawArc(oval, -90f, sweep, false, glow)
            canvas.drawArc(oval, -90f, sweep, false, arc)
        }

        val sec = (left + 999L) / 1000L
        if (sec != lastSec) {
            lastSec = sec
            digits = fmtCountdown(left)
        }
        big.textSize = (if (digits.length > 5) 40f else 54f) * d
        val baseline = cy + big.textSize * 0.18f
        canvas.drawText(digits, cx, baseline, big)
        small.textSize = 13f * d
        canvas.drawText("remaining", cx, baseline + 30f * d, small)
    }
}

private fun fmtDuration(min: Int): String {
    val h = min / 60
    val m = min % 60
    return if (h > 0) {
        if (m > 0) "$h h $m min" else "$h h"
    } else {
        "$m min"
    }
}

private fun fmtCountdown(ms: Long): String {
    val t = maxOf(0L, (ms + 999L) / 1000L)
    val h = t / 3600L
    val m = (t % 3600L) / 60L
    val s = t % 60L
    return if (h > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%02d:%02d", m, s)
    }
}
