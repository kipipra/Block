package com.focuslock.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckedTextView
import android.widget.ListView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.Date
import java.util.Locale

private object C {
    val BG: Int = Color.parseColor("#0B0E14")
    val SURFACE: Int = Color.parseColor("#161C28")
    val SURFACE2: Int = Color.parseColor("#212A3A")
    val LINE: Int = Color.parseColor("#2B3446")
    val FG: Int = Color.parseColor("#EEF0F5")
    val MUTED: Int = Color.parseColor("#9AA4B8")
    val ACCENT: Int = Color.parseColor("#E0B04F")
    val INK: Int = Color.parseColor("#1A1305")
    val DANGER: Int = Color.parseColor("#EF8272")
    val OK: Int = Color.parseColor("#6FC79C")
}

class MainActivity : Activity() {

    companion object {
        const val MAX_MIN = 720          // 12 h, same limit as before
        const val STEP = 5               // slider moves in 5 minute steps
        val PRESETS = intArrayOf(15, 25, 45, 60, 120)
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences

    private var minutes = 25
    private var syncing = false

    private lateinit var root: ScrollView
    private lateinit var permStatus: TextView
    private lateinit var permHint: TextView
    private lateinit var permBtn: Button
    private lateinit var setupBox: LinearLayout
    private lateinit var lockedBox: LinearLayout
    private lateinit var lockedDigits: TextView
    private lateinit var lockedUntil: TextView
    private lateinit var durText: TextView
    private lateinit var helper: TextView
    private lateinit var seek: SeekBar
    private lateinit var hoursIn: EditText
    private lateinit var minsIn: EditText
    private lateinit var lockBtn: Button
    private lateinit var allowedSummary: TextView
    private lateinit var lastBlockedView: TextView
    private lateinit var endText: TextView
    private val presetBtns = ArrayList<Button>()

    private val ticker = object : Runnable {
        override fun run() {
            refreshUi()
            if (::endText.isInitialized) refreshEndEstimate()
            handler.postDelayed(this, 1000L)
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
    }

    override fun onResume() {
        super.onResume()
        refreshLastBlocked()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    // ------------------------------------------------------------------ UI

    private fun buildUi(): View {
        root = ScrollView(this)
        root.setBackgroundColor(C.BG)
        root.isFillViewport = true
        root.isFocusableInTouchMode = true
        root.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(20), dp(36), dp(20), dp(40))
        root.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        col.addView(text("FocusLock", 30f, C.FG, bold = true), lp(0))
        col.addView(text("Pick a time. When it starts, the phone stays locked until it ends.", 14f, C.MUTED), lp(6))

        // permission card
        val perm = LinearLayout(this)
        perm.orientation = LinearLayout.VERTICAL
        perm.setPadding(dp(16), dp(16), dp(16), dp(16))
        perm.background = rounded(C.SURFACE, dp(14), C.LINE, dp(1))
        permStatus = text("", 16f, C.FG, bold = true)
        permHint = text(
            "Open Accessibility settings, tap FocusLock and switch it on. If the switch is greyed out: Settings, Apps, FocusLock, three dots at the top right, Allow restricted settings.",
            13f, C.MUTED
        )
        permBtn = button("Turn on lock permission", C.ACCENT, C.INK)
        permBtn.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        perm.addView(permStatus, lp(0))
        perm.addView(permHint, lp(8))
        perm.addView(permBtn, lp(12))
        col.addView(perm, lp(24))

        // ---- locked state
        lockedBox = LinearLayout(this)
        lockedBox.orientation = LinearLayout.VERTICAL
        lockedBox.gravity = Gravity.CENTER_HORIZONTAL
        lockedBox.setPadding(dp(16), dp(32), dp(16), dp(32))
        lockedBox.background = rounded(C.SURFACE, dp(16), C.LINE, dp(1))
        lockedBox.visibility = View.GONE
        lockedBox.addView(text("LOCKED", 12f, C.ACCENT, bold = true, center = true), lp(0))
        lockedDigits = text("00:00", 56f, C.FG, mono = true, center = true)
        lockedBox.addView(lockedDigits, lp(16))
        lockedUntil = text("", 15f, C.MUTED, center = true)
        lockedBox.addView(lockedUntil, lp(8))
        lockedBox.addView(text("This lock cannot be cancelled. Come back when the timer ends.", 13f, C.MUTED, center = true), lp(20))
        col.addView(lockedBox, lp(24))

        // ---- setup state
        setupBox = LinearLayout(this)
        setupBox.orientation = LinearLayout.VERTICAL
        col.addView(setupBox, lp(0))

        setupBox.addView(text("LOCK FOR", 12f, C.MUTED, bold = true), lp(28))
        durText = text("25 min", 44f, C.ACCENT, bold = true)
        setupBox.addView(durText, lp(4))
        endText = text("", 15f, C.FG)
        setupBox.addView(endText, lp(2))

        seek = SeekBar(this)
        seek.max = MAX_MIN / STEP - 1
        seek.progressTintList = ColorStateList.valueOf(C.ACCENT)
        seek.thumbTintList = ColorStateList.valueOf(C.ACCENT)
        seek.progressBackgroundTintList = ColorStateList.valueOf(C.SURFACE2)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) applyMinutes((p + 1) * STEP, fields = true, slider = false)
            }

            override fun onStartTrackingTouch(s: SeekBar?) {
                dropFocus()
            }

            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        setupBox.addView(seek, lp(10))

        val scale = LinearLayout(this)
        scale.orientation = LinearLayout.HORIZONTAL
        scale.addView(text("5 min", 12f, C.MUTED, mono = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val maxLabel = text("12 h", 12f, C.MUTED, mono = true)
        maxLabel.gravity = Gravity.END
        scale.addView(maxLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        setupBox.addView(scale, lp(0))

        // presets
        val presets = LinearLayout(this)
        presets.orientation = LinearLayout.HORIZONTAL
        for (m in PRESETS) {
            val b = button(fmtDuration(m), C.SURFACE2, C.FG)
            b.textSize = 13f
            b.minHeight = dp(44)
            b.minimumHeight = dp(44)
            b.minWidth = 0
            b.minimumWidth = 0
            b.setPadding(0, 0, 0, 0)
            b.setOnClickListener {
                dropFocus()
                applyMinutes(m, fields = true, slider = true)
            }
            val p = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            p.leftMargin = dp(3)
            p.rightMargin = dp(3)
            presets.addView(b, p)
            presetBtns.add(b)
        }
        setupBox.addView(presets, lp(18))

        // custom hours + minutes
        setupBox.addView(text("OR TYPE AN EXACT TIME", 12f, C.MUTED, bold = true), lp(28))
        val custom = LinearLayout(this)
        custom.orientation = LinearLayout.HORIZONTAL
        custom.gravity = Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL
        hoursIn = numberField(2)
        minsIn = numberField(3)
        custom.addView(hoursIn, LinearLayout.LayoutParams(dp(88), dp(64)))
        custom.addView(unit("h"), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val gap = View(this)
        custom.addView(gap, LinearLayout.LayoutParams(dp(16), 1))
        custom.addView(minsIn, LinearLayout.LayoutParams(dp(88), dp(64)))
        custom.addView(unit("min"), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setupBox.addView(custom, lp(10))

        helper = text("", 13f, C.MUTED, center = true)
        setupBox.addView(helper, lp(10))

        // allowed apps
        setupBox.addView(text("APPS ALLOWED WHILE LOCKED", 12f, C.MUTED, bold = true), lp(28))
        allowedSummary = text("", 14f, C.FG)
        setupBox.addView(allowedSummary, lp(10))
        val pickBtn = button("Choose apps (up to 6)", C.SURFACE2, C.FG)
        pickBtn.setOnClickListener { dropFocus(); showAppPicker() }
        setupBox.addView(pickBtn, lp(10))
        setupBox.addView(text("Phone calls always work. The apps you pick stay usable during the lock and can only be changed before it starts.", 13f, C.MUTED), lp(8))
        refreshAllowedSummary()
        lastBlockedView = text("", 12f, C.MUTED)
        setupBox.addView(lastBlockedView, lp(14))
        refreshLastBlocked()

        lockBtn = button("Lock", C.ACCENT, C.INK)
        lockBtn.setOnClickListener { onLockClicked() }
        setupBox.addView(lockBtn, lp(24))

        return root
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
        e.background = rounded(C.SURFACE2, dp(12), C.LINE, dp(1))
        e.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!syncing) onFieldsEdited()
            }
        })
        e.setOnFocusChangeListener { _, has ->
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

    // ------------------------------------------------------------------ time state

    /** Updates the big readout, slider, presets, fields and the lock button from one number of minutes. */
    private fun applyMinutes(raw: Int, fields: Boolean, slider: Boolean) {
        syncing = true
        minutes = raw.coerceIn(0, MAX_MIN)

        durText.text = if (minutes == 0) "0 min" else fmtDuration(minutes)
        refreshEndEstimate()

        if (slider) {
            seek.progress = ((minutes + STEP / 2) / STEP - 1).coerceIn(0, seek.max)
        }
        if (fields) {
            hoursIn.setText((minutes / 60).toString())
            minsIn.setText((minutes % 60).toString())
        }
        for (i in presetBtns.indices) styleChip(presetBtns[i], PRESETS[i] == minutes)

        when {
            raw > MAX_MIN -> {
                helper.text = "The longest lock is 12 h, so that is what will be used."
                helper.setTextColor(C.DANGER)
            }
            minutes == 0 -> {
                helper.text = "Enter at least 1 minute."
                helper.setTextColor(C.DANGER)
            }
            else -> {
                helper.text = "Hours and minutes add up. 90 min is the same as 1 h 30 min."
                helper.setTextColor(C.MUTED)
            }
        }

        val ok = minutes > 0
        lockBtn.isEnabled = ok
        lockBtn.alpha = if (ok) 1f else 0.4f
        lockBtn.text = if (ok) "Lock for " + fmtDuration(minutes) else "Set a time"
        syncing = false
    }

    /** Shows when the phone would unlock if the lock started right now. */
    private fun refreshEndEstimate() {
        if (minutes <= 0) {
            endText.text = ""
            return
        }
        val end = System.currentTimeMillis() + minutes * 60_000L
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
        endText.text = "Unlocks at about " + clock(end) + ", " + day
    }

    private fun onFieldsEdited() {
        val h = hoursIn.text.toString().toIntOrNull() ?: 0
        val m = minsIn.text.toString().toIntOrNull() ?: 0
        applyMinutes(h * 60 + m, fields = false, slider = true)
    }

    private fun styleChip(b: Button, selected: Boolean) {
        b.background = rounded(if (selected) C.ACCENT else C.SURFACE2, dp(12))
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
            .sortedBy { it.label.lowercase(Locale.ROOT) }
    }

    private fun refreshLastBlocked() {
        val lb = LockAccessibilityService.lastBlocked
        lastBlockedView.text = "Last blocked: " + (if (lb.isEmpty()) "nothing yet" else lb)
    }

    private fun refreshAllowedSummary() {
        val pm = packageManager
        val names = LockAccessibilityService.allowedApps.mapNotNull {
            try { pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString() } catch (_: Exception) { null }
        }
        allowedSummary.text = if (names.isEmpty()) "None. Only phone calls will work." else names.joinToString(", ")
    }

    private fun showAppPicker() {
        if (LockAccessibilityService.isLocked()) return
        val apps = launchableApps()
        val chosen = LinkedHashSet<String>(LockAccessibilityService.allowedApps)
        val max = LockAccessibilityService.MAX_ALLOWED
        val iconSize = dp(36)

        var dialog: AlertDialog? = null
        val list = ListView(this)
        list.adapter = object : BaseAdapter() {
            override fun getCount() = apps.size
            override fun getItem(i: Int): Any = apps[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, convert: View?, parent: ViewGroup?): View {
                val v = (convert as? CheckedTextView) ?: CheckedTextView(this@MainActivity).apply {
                    setTextColor(C.FG)
                    textSize = 16f
                    gravity = Gravity.CENTER_VERTICAL
                    compoundDrawablePadding = dp(14)
                    setPadding(dp(16), dp(10), dp(16), dp(10))
                }
                val a = apps[i]
                v.text = a.label
                val d = a.icon.constantState?.newDrawable()?.mutate() ?: a.icon
                d.setBounds(0, 0, iconSize, iconSize)
                v.setCompoundDrawables(d, null, null, null)
                v.text = (if (a.pkg in chosen) "\u2713  " else "      ") + a.label
                v.setTextColor(if (a.pkg in chosen) C.ACCENT else C.FG)
                return v
            }
        }
        list.setOnItemClickListener { _, _, pos, _ ->
            val pkg = apps[pos].pkg
            if (pkg in chosen) {
                chosen.remove(pkg)
            } else if (chosen.size >= max) {
                Toast.makeText(this, "You can pick up to $max apps.", Toast.LENGTH_SHORT).show()
            } else {
                chosen.add(pkg)
            }
            // Saved on every tap, so closing the dialog any way never loses the choice.
            LockAccessibilityService.allowedApps = chosen.toList()
            refreshAllowedSummary()
            dialog?.setTitle("Allowed apps (" + chosen.size + " of $max)")
            (list.adapter as BaseAdapter).notifyDataSetChanged()
        }

        dialog = AlertDialog.Builder(this)
            .setTitle("Allowed apps (" + chosen.size + " of $max)")
            .setView(list)
            .setPositiveButton("Done", null)
            .show()
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
        val end = System.currentTimeMillis() + minutes * 60_000L
        AlertDialog.Builder(this)
            .setTitle("Lock for " + fmtDuration(minutes) + "?")
            .setMessage("Your phone will stay locked until " + clock(end) + ". It cannot be cancelled once it starts.")
            .setPositiveButton("Lock now") { _, _ -> startLock() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startLock() {
        if (LockAccessibilityService.isLocked() || minutes <= 0) return
        prefs.edit().putInt("minutes", minutes).apply()
        LockAccessibilityService.lockEndTime = System.currentTimeMillis() + minutes * 60_000L
        startForegroundService(Intent(this, LockService::class.java))
        refreshUi()
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val target = ComponentName(this, LockAccessibilityService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == target }
    }

    private fun refreshUi() {
        val locked = LockAccessibilityService.isLocked()
        val on = isAccessibilityEnabled()

        permStatus.text = if (on) "Lock permission is on" else "Lock permission is off"
        permStatus.setTextColor(if (on) C.OK else C.DANGER)
        val vis = if (on) View.GONE else View.VISIBLE
        permHint.visibility = vis
        permBtn.visibility = vis
        (permStatus.parent as View).visibility = if (locked) View.GONE else View.VISIBLE

        setupBox.visibility = if (locked) View.GONE else View.VISIBLE
        lockedBox.visibility = if (locked) View.VISIBLE else View.GONE

        if (locked) {
            val end = LockAccessibilityService.lockEndTime
            lockedDigits.text = fmtCountdown(end - System.currentTimeMillis())
            lockedUntil.text = "Unlocks at " + clock(end)
        }
    }

    // ------------------------------------------------------------------ small helpers

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun clock(ts: Long): String = DateFormat.getTimeFormat(this).format(Date(ts))

    private fun lp(top: Int): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        p.topMargin = dp(top)
        return p
    }

    private fun rounded(fill: Int, radius: Int, stroke: Int = 0, strokePx: Int = 0): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(fill)
        g.cornerRadius = radius.toFloat()
        if (strokePx > 0) g.setStroke(strokePx, stroke)
        return g
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

    private fun button(label: String, fill: Int, textColor: Int): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.setTextColor(textColor)
        b.textSize = 16f
        b.minHeight = dp(52)
        b.minimumHeight = dp(52)
        b.background = rounded(fill, dp(12))
        b.stateListAnimator = null
        return b
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
