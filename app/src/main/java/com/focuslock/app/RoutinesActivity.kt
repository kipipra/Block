package com.focuslock.app

import android.app.Activity
import android.app.Dialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

private const val MATCH = -1
private const val WRAP = -2

/** A scroll view that never grows taller than [maxH], so tall sheets scroll instead of running off screen. */
private class MaxHeightScroll(ctx: Context, private val maxH: Int) : ScrollView(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val parentH = View.MeasureSpec.getSize(heightMeasureSpec)
        val h = if (parentH > 0) minOf(parentH, maxH) else maxH
        super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.AT_MOST))
    }
}

class RoutinesActivity : Activity() {

    companion object {
        // Monday first. Each number is the bit used for that day (bit 0 = Sunday).
        private val DAY_BITS = intArrayOf(1, 2, 3, 4, 5, 6, 0)
        private val DAY_LETTERS = arrayOf("M", "T", "W", "T", "F", "S", "S")
    }

    private val handler = Handler(Looper.getMainLooper())
    private var routines: MutableList<Routine> = ArrayList()

    private lateinit var root: ScrollView
    private lateinit var col: LinearLayout
    private lateinit var warnCard: LinearLayout
    private lateinit var statusTag: TextView
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var masterSwitch: Switch
    private lateinit var listBox: LinearLayout
    private var settingMaster = false

    private val ticker = object : Runnable {
        override fun run() {
            if (LockAccessibilityService.isLocked()) {
                finish()
                return
            }
            // If a routine is due right now, it locks the phone and we step out of the way.
            if (Routines.reconcile(this@RoutinesActivity)) {
                finish()
                return
            }
            refreshStatus()
            refreshWarning()
            handler.postDelayed(this, 15_000L)
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LockAccessibilityService.init(this)
        window.statusBarColor = C.BG
        window.navigationBarColor = C.BG
        setContentView(buildUi())
        routines = Routines.load(this)
        rebuildList()
        refreshStatus()
        refreshWarning()
        if (savedInstanceState == null) playEntrance()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ main layout

    private fun buildUi(): View {
        root = ScrollView(this)
        val glow = GradientDrawable()
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT)
        glow.setGradientCenter(0.5f, 0.0f)
        glow.setGradientRadius(dp(480).toFloat())
        glow.setColors(intArrayOf(Color.argb(64, 60, 216, 236), Color.argb(0, 60, 216, 236)))
        root.background = android.graphics.drawable.LayerDrawable(arrayOf<Drawable>(ColorDrawable(C.BG), glow))
        root.isFillViewport = true
        root.isVerticalScrollBarEnabled = false

        col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(20), dp(30), dp(20), dp(40))
        root.addView(col, ViewGroup.LayoutParams(MATCH, WRAP))

        // header with a back arrow
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        val back = text("\u2039", 38f, C.ACCENT, bold = true)
        back.gravity = Gravity.CENTER
        back.setPadding(dp(4), 0, dp(16), dp(6))
        back.pressable(0.9f)
        back.setOnClickListener { finish() }
        header.addView(back, LinearLayout.LayoutParams(WRAP, WRAP))
        val titles = LinearLayout(this)
        titles.orientation = LinearLayout.VERTICAL
        titles.addView(text("Routines", 26f, C.FG, bold = true))
        titles.addView(text("Locks that start and stop on their own", 13f, C.MUTED))
        header.addView(titles, LinearLayout.LayoutParams(0, WRAP, 1f))
        col.addView(header, lp(0))

        // permission warning
        warnCard = card()
        warnCard.visibility = View.GONE
        warnCard.addView(text("Lock permission is off", 15f, C.DANGER, bold = true), lp(0))
        warnCard.addView(text("Routines cannot lock your phone until it is switched on.", 13f, C.MUTED), lp(6))
        val warnBtn = secondaryButton("Open settings")
        warnBtn.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        warnCard.addView(warnBtn, lp(12))
        col.addView(warnCard, lp(18))

        // status card with the master switch
        val hero = card()
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        statusTag = label("NEXT UP")
        top.addView(statusTag, LinearLayout.LayoutParams(0, WRAP, 1f))
        val auto = text("Auto-lock", 12f, C.MUTED, bold = true)
        auto.setPadding(0, 0, dp(8), 0)
        top.addView(auto, LinearLayout.LayoutParams(WRAP, WRAP))
        masterSwitch = Switch(this)
        styleSwitch(masterSwitch)
        masterSwitch.isChecked = Routines.isMasterOn(this)
        masterSwitch.setOnCheckedChangeListener { _, on ->
            if (!settingMaster) onMasterToggled(on)
        }
        top.addView(masterSwitch, LinearLayout.LayoutParams(WRAP, WRAP))
        hero.addView(top, lp(0))
        statusTitle = text("", 26f, C.ACCENT, bold = true)
        hero.addView(statusTitle, lp(8))
        statusDetail = text("", 14f, C.MUTED)
        hero.addView(statusDetail, lp(4))
        col.addView(hero, lp(18))

        // routine cards
        listBox = LinearLayout(this)
        listBox.orientation = LinearLayout.VERTICAL
        col.addView(listBox, lp(6))

        val add = primaryButton("+  Add routine")
        add.setOnClickListener { showEditor(null) }
        col.addView(add, lp(18))

        // quick add
        val quick = card()
        quick.addView(label("QUICK ADD"), lp(0))
        quick.addView(
            quickRow(
                "Morning + evening", "8 AM to 1 PM, then 5 PM to 11 PM, every day"
            ) {
                quickAdd(
                    listOf(
                        Routine(0, "Morning focus", 480, 780, 127, true),
                        Routine(0, "Evening focus", 1020, 1380, 127, true)
                    )
                )
            }, lp(12)
        )
        quick.addView(
            quickRow("Deep work", "9 AM to 12 PM, weekdays") {
                quickAdd(listOf(Routine(0, "Deep work", 540, 720, 62, true)))
            }, lp(8)
        )
        quick.addView(
            quickRow("Night wind-down", "11 PM to 7 AM, every night") {
                quickAdd(listOf(Routine(0, "Night wind-down", 1380, 420, 127, true)))
            }, lp(8)
        )
        col.addView(quick, lp(22))

        // how it works
        val info = card()
        info.addView(label("HOW IT WORKS"), lp(0))
        info.addView(
            text(
                "\u2022  A routine starts by itself at its time, even if the app is closed or the phone restarted.\n" +
                    "\u2022  Once a lock starts, it cannot be cancelled.\n" +
                    "\u2022  It uses the apps you chose on the main screen.\n" +
                    "\u2022  FORTIS sets a system alarm to wake up on time, so you may see a small alarm icon.",
                13f, C.MUTED
            ), lp(10)
        )
        val batt = secondaryButton("Keep alarms reliable")
        batt.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(this, "Open Settings, Battery, and allow FORTIS to run in the background.", Toast.LENGTH_LONG).show()
            }
        }
        info.addView(batt, lp(14))
        col.addView(info, lp(14))

        return root
    }

    private fun playEntrance() {
        for (i in 0 until col.childCount) {
            val v = col.getChildAt(i)
            if (v.visibility != View.VISIBLE) continue
            v.alpha = 0f
            v.translationY = dp(18).toFloat()
            v.animate()
                .alpha(1f).translationY(0f)
                .setStartDelay(50L + i * 60L)
                .setDuration(380L)
                .setInterpolator(DecelerateInterpolator(1.8f))
                .start()
        }
    }

    // ------------------------------------------------------------------ status

    private fun refreshStatus() {
        val now = System.currentTimeMillis()
        val master = Routines.isMasterOn(this)
        if (masterSwitch.isChecked != master) {
            settingMaster = true
            masterSwitch.isChecked = master
            settingMaster = false
        }
        val due = Routines.due(this, routines, now)
        val next = Routines.nextUp(routines, now)
        when {
            routines.isEmpty() -> {
                statusTag.text = "ROUTINES"
                statusTitle.text = "Nothing scheduled"
                statusDetail.text = "Add a routine and FORTIS locks your phone on time, even if you forget."
            }
            !master -> {
                statusTag.text = "PAUSED"
                statusTitle.text = "Auto-lock is off"
                statusDetail.text = "Switch it on and your routines start locking again."
            }
            due != null -> {
                statusTag.text = "ACTIVE NOW"
                statusTitle.text = due.name
                statusDetail.text = "Locked until " + Routines.clockLabel(this, due.end)
            }
            next != null -> {
                statusTag.text = "NEXT UP"
                statusTitle.text = next.routine.name
                statusDetail.text = Routines.whenLabel(this, next.at).replaceFirstChar { it.uppercase() } +
                    "  \u00B7  " + Routines.inLabel(next.at - now)
            }
            else -> {
                statusTag.text = "ROUTINES"
                statusTitle.text = "All routines are off"
                statusDetail.text = "Switch one on to lock automatically."
            }
        }
    }

    private fun refreshWarning() {
        warnCard.visibility = if (isAccessibilityEnabled()) View.GONE else View.VISIBLE
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val target = ComponentName(this, LockAccessibilityService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == target }
    }

    // ------------------------------------------------------------------ routine list

    private fun rebuildList() {
        listBox.removeAllViews()
        if (routines.isEmpty()) {
            val empty = text("No routines yet. Tap Add routine, or use Quick add below.", 13.5f, C.MUTED, center = true)
            empty.setPadding(dp(10), dp(16), dp(10), dp(6))
            listBox.addView(empty, lp(0))
            return
        }
        val now = System.currentTimeMillis()
        val master = Routines.isMasterOn(this)
        for (r in routines.sortedBy { it.startMin }) {
            listBox.addView(routineCard(r, now, master), lp(12))
        }
    }

    private fun routineCard(r: Routine, now: Long, master: Boolean): View {
        val active = master && r.enabled && Routines.activeEnd(r, now) != null
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(18), dp(16), dp(18), dp(16))
        val bg = gradientDrawable(C.SURFACE_HI, C.SURFACE, dp(22))
        if (active) bg.setStroke(dp(2), C.ACCENT) else bg.setStroke(dp(1), C.LINE)
        c.background = withRipple(bg, dp(22))
        c.alpha = if (r.enabled) 1f else 0.6f

        val row1 = LinearLayout(this)
        row1.orientation = LinearLayout.HORIZONTAL
        row1.gravity = Gravity.CENTER_VERTICAL
        val nameT = text(r.name, 17f, C.FG, bold = true)
        nameT.maxLines = 1
        nameT.ellipsize = TextUtils.TruncateAt.END
        row1.addView(nameT, LinearLayout.LayoutParams(0, WRAP, 1f))
        if (active) {
            val badge = text("ACTIVE NOW", 10f, C.INK, bold = true)
            badge.letterSpacing = 0.08f
            badge.setPadding(dp(8), dp(3), dp(8), dp(3))
            badge.background = roundedDrawable(C.ACCENT, dp(8))
            val bp = LinearLayout.LayoutParams(WRAP, WRAP)
            bp.rightMargin = dp(10)
            row1.addView(badge, bp)
        }
        val sw = Switch(this)
        styleSwitch(sw)
        sw.isChecked = r.enabled
        sw.setOnCheckedChangeListener { _, on ->
            val updated = routines.map { if (it.id == r.id) it.copy().also { x -> x.enabled = on } else it }.toMutableList()
            commit(updated, after = {}, onCancel = { rebuildList() })
        }
        row1.addView(sw, LinearLayout.LayoutParams(WRAP, WRAP))
        c.addView(row1)

        val times = text(
            Routines.timeLabel(this, r.startMin) + "  \u2192  " + Routines.timeLabel(this, r.endMin),
            21f, if (r.enabled) C.ACCENT else C.MUTED, bold = true
        )
        c.addView(times, lp(10))
        val meta = Routines.lengthLabel(r.lengthMin) + (if (r.overnight) "  \u00B7  ends the next day" else "")
        c.addView(text(meta, 12.5f, C.MUTED), lp(2))

        val daysRow = LinearLayout(this)
        daysRow.orientation = LinearLayout.HORIZONTAL
        for (i in DAY_BITS.indices) {
            val on = ((r.days shr DAY_BITS[i]) and 1) == 1
            val d = text(DAY_LETTERS[i], 12f, if (on) C.INK else C.MUTED, bold = true, center = true)
            d.background = roundedDrawable(if (on) C.ACCENT else C.SURFACE2, dp(15))
            val dp2 = LinearLayout.LayoutParams(dp(30), dp(30))
            dp2.rightMargin = dp(6)
            daysRow.addView(d, dp2)
        }
        c.addView(daysRow, lp(12))

        val nextTs = Routines.nextStart(r, now)
        if (master && r.enabled && nextTs != null && !active) {
            c.addView(text("Next: " + Routines.whenLabel(this, nextTs), 12.5f, C.MUTED), lp(10))
        }
        c.pressable(0.985f)
        c.setOnClickListener { showEditor(r) }
        return c
    }

    private fun quickRow(title: String, sub: String, onClick: () -> Unit): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.VERTICAL
        row.setPadding(dp(16), dp(12), dp(16), dp(12))
        row.background = withRipple(roundedDrawable(C.SURFACE2, dp(16)), dp(16))
        row.addView(text(title, 15f, C.FG, bold = true))
        row.addView(text(sub, 12.5f, C.MUTED), lp(2))
        row.pressable(0.98f)
        row.setOnClickListener { onClick() }
        return row
    }

    private fun quickAdd(items: List<Routine>) {
        val stamp = System.currentTimeMillis()
        val updated = routines.toMutableList()
        for ((i, r) in items.withIndex()) {
            updated.add(Routine(stamp + i, r.name, r.startMin, r.endMin, r.days, true))
        }
        commit(updated, after = {
            Toast.makeText(this, if (items.size > 1) "Routines added" else "Routine added", Toast.LENGTH_SHORT).show()
        }, onCancel = {})
    }

    private fun onMasterToggled(on: Boolean) {
        Routines.setMasterOn(this, on)
        if (on) {
            val due = Routines.due(this, routines, System.currentTimeMillis())
            if (due != null && !LockAccessibilityService.isLocked() && Routines.permissionOn(this)) {
                confirmStartNow(due, {
                    Routines.reconcile(this)
                    finish()
                }, {
                    Routines.setMasterOn(this, false)
                    refreshStatus()
                    rebuildList()
                })
                return
            }
        }
        refreshStatus()
        rebuildList()
    }

    /**
     * Saves a changed list. If that would start a lock right now, asks first.
     * [after] runs once the change is saved. [onCancel] runs if the person backs out.
     */
    private fun commit(newList: MutableList<Routine>, after: () -> Unit, onCancel: () -> Unit) {
        val due = Routines.due(this, newList, System.currentTimeMillis())
        val doCommit: () -> Unit = {
            routines = newList
            Routines.save(this, routines)
            Routines.reconcile(this)
            rebuildList()
            refreshStatus()
            after()
            if (LockAccessibilityService.isLocked()) finish()
        }
        if (due != null && !LockAccessibilityService.isLocked() && Routines.permissionOn(this)) {
            confirmStartNow(due, doCommit, onCancel)
        } else {
            doCommit()
        }
    }

    private fun confirmStartNow(due: Due, yes: () -> Unit, no: () -> Unit) {
        val (dlg, sheet) = newSheet()
        var decided = false
        sheet.addView(text("This lock starts now", 22f, C.FG, bold = true), lp(0))
        sheet.addView(
            text(
                due.name + " is scheduled for right now. Your phone will lock until " +
                    Routines.clockLabel(this, due.end) + " and it cannot be cancelled.",
                14f, C.MUTED
            ), lp(8)
        )
        val go = primaryButton("Lock now")
        go.setOnClickListener {
            decided = true
            go.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            dlg.dismiss()
            yes()
        }
        val back = secondaryButton("Go back")
        back.setOnClickListener { closeSheet(dlg, sheet) }
        sheet.addView(go, lp(20))
        sheet.addView(back, lp(10))
        dlg.setOnDismissListener { if (!decided) no() }
        showSheet(dlg, sheet)
    }

    // ------------------------------------------------------------------ editor

    private inner class TimeWheel(private val onChange: (Int) -> Unit) {
        val view = LinearLayout(this@RoutinesActivity)
        private val h24 = DateFormat.is24HourFormat(this@RoutinesActivity)
        private val hour = NumberPicker(this@RoutinesActivity)
        private val minute = NumberPicker(this@RoutinesActivity)
        private val ampm = NumberPicker(this@RoutinesActivity)
        private var muted = false

        init {
            view.orientation = LinearLayout.HORIZONTAL
            view.gravity = Gravity.CENTER
            if (h24) {
                hour.minValue = 0
                hour.maxValue = 23
                hour.displayedValues = Array(24) { String.format(Locale.US, "%02d", it) }
            } else {
                hour.minValue = 1
                hour.maxValue = 12
                hour.displayedValues = Array(12) { (it + 1).toString() }
            }
            minute.minValue = 0
            minute.maxValue = 59
            minute.displayedValues = Array(60) { String.format(Locale.US, "%02d", it) }
            ampm.minValue = 0
            ampm.maxValue = 1
            ampm.displayedValues = arrayOf("AM", "PM")
            val pickers = if (h24) listOf(hour, minute) else listOf(hour, minute, ampm)
            for (p in pickers) {
                p.wrapSelectorWheel = true
                p.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                p.setOnValueChangedListener { _, _, _ -> if (!muted) onChange(value) }
                view.addView(p, LinearLayout.LayoutParams(dp(84), WRAP))
            }
            ampm.wrapSelectorWheel = false
        }

        var value: Int
            get() {
                val m = minute.value
                return if (h24) {
                    hour.value * 60 + m
                } else {
                    ((hour.value % 12) + (if (ampm.value == 1) 12 else 0)) * 60 + m
                }
            }
            set(v) {
                muted = true
                val hh = v / 60
                if (h24) {
                    hour.value = hh
                } else {
                    hour.value = if (hh % 12 == 0) 12 else hh % 12
                    ampm.value = if (hh >= 12) 1 else 0
                }
                minute.value = v % 60
                muted = false
            }
    }

    private fun showEditor(existing: Routine?) {
        val (dlg, sheet) = newSheet()
        var start = existing?.startMin ?: 480
        var end = existing?.endMin ?: 780
        var days = existing?.days ?: 127
        var editingEnd = false

        val scroll = MaxHeightScroll(this, (resources.displayMetrics.heightPixels * 0.82f).toInt())
        scroll.isVerticalScrollBarEnabled = false
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        scroll.addView(box, ViewGroup.LayoutParams(MATCH, WRAP))
        sheet.addView(scroll, LinearLayout.LayoutParams(MATCH, WRAP))

        // title row, with Delete when editing
        val titleRow = LinearLayout(this)
        titleRow.orientation = LinearLayout.HORIZONTAL
        titleRow.gravity = Gravity.CENTER_VERTICAL
        titleRow.addView(
            text(if (existing == null) "New routine" else "Edit routine", 22f, C.FG, bold = true),
            LinearLayout.LayoutParams(0, WRAP, 1f)
        )
        if (existing != null) {
            val del = text("Delete", 14f, C.DANGER, bold = true)
            del.setPadding(dp(10), dp(6), dp(4), dp(6))
            var armed = false
            del.setOnClickListener {
                if (!armed) {
                    armed = true
                    del.text = "Tap again to delete"
                    handler.postDelayed({
                        armed = false
                        del.text = "Delete"
                    }, 3000L)
                } else {
                    commit(routines.filter { it.id != existing.id }.toMutableList(), after = { closeSheet(dlg, sheet) }, onCancel = {})
                }
            }
            titleRow.addView(del, LinearLayout.LayoutParams(WRAP, WRAP))
        }
        box.addView(titleRow, lp(0))

        // name
        val nameEdit = EditText(this)
        nameEdit.setText(existing?.name ?: "Focus block")
        nameEdit.hint = "Name"
        nameEdit.setSingleLine(true)
        nameEdit.textSize = 16f
        nameEdit.setTextColor(C.FG)
        nameEdit.setHintTextColor(C.MUTED)
        nameEdit.setPadding(dp(16), 0, dp(16), 0)
        nameEdit.background = roundedDrawable(C.SURFACE2, dp(14))
        nameEdit.imeOptions = EditorInfo.IME_ACTION_DONE
        nameEdit.filters = arrayOf<android.text.InputFilter>(android.text.InputFilter.LengthFilter(24))
        val nameLp = LinearLayout.LayoutParams(MATCH, dp(50))
        nameLp.topMargin = dp(16)
        box.addView(nameEdit, nameLp)

        // start / end tiles
        val startLabel = label("STARTS")
        val startTime = text("", 22f, C.FG, bold = true)
        val endLabel = label("ENDS")
        val endTime = text("", 22f, C.FG, bold = true)
        val startTile = LinearLayout(this)
        startTile.orientation = LinearLayout.VERTICAL
        startTile.setPadding(dp(16), dp(12), dp(16), dp(12))
        startTile.addView(startLabel)
        startTile.addView(startTime, lp(2))
        val endTile = LinearLayout(this)
        endTile.orientation = LinearLayout.VERTICAL
        endTile.setPadding(dp(16), dp(12), dp(16), dp(12))
        endTile.addView(endLabel)
        endTile.addView(endTime, lp(2))
        val tiles = LinearLayout(this)
        tiles.orientation = LinearLayout.HORIZONTAL
        val tl = LinearLayout.LayoutParams(0, WRAP, 1f)
        tl.rightMargin = dp(5)
        val tr = LinearLayout.LayoutParams(0, WRAP, 1f)
        tr.leftMargin = dp(5)
        tiles.addView(startTile, tl)
        tiles.addView(endTile, tr)
        box.addView(tiles, lp(14))

        val lenText = text("", 13f, C.MUTED, center = true)
        box.addView(lenText, lp(10))

        fun styleTile(t: LinearLayout, selected: Boolean) {
            val fill = if (selected) Color.argb(30, 60, 216, 236) else C.SURFACE2
            val stroke = if (selected) C.ACCENT else C.LINE
            t.background = withRipple(roundedDrawable(fill, dp(16), stroke, dp(if (selected) 2 else 1)), dp(16))
        }

        fun refresh() {
            startTime.text = Routines.timeLabel(this, start)
            endTime.text = Routines.timeLabel(this, end)
            styleTile(startTile, !editingEnd)
            styleTile(endTile, editingEnd)
            startTime.setTextColor(if (!editingEnd) C.ACCENT else C.FG)
            endTime.setTextColor(if (editingEnd) C.ACCENT else C.FG)
            if (start == end) {
                lenText.text = "Start and end cannot be the same"
                lenText.setTextColor(C.DANGER)
            } else {
                val len = if (end > start) end - start else end + 1440 - start
                lenText.text = "Lasts " + Routines.lengthLabel(len) + (if (end < start) "  \u00B7  ends the next day" else "")
                lenText.setTextColor(C.MUTED)
            }
        }

        val wheel = TimeWheel { v ->
            if (editingEnd) end = v else start = v
            refresh()
        }
        wheel.value = start
        box.addView(wheel.view, lp(6))

        startTile.setOnClickListener {
            editingEnd = false
            wheel.value = start
            refresh()
        }
        endTile.setOnClickListener {
            editingEnd = true
            wheel.value = end
            refresh()
        }
        startTile.pressable(0.98f)
        endTile.pressable(0.98f)

        // days
        box.addView(label("REPEAT"), lp(14))
        val dayRow = LinearLayout(this)
        dayRow.orientation = LinearLayout.HORIZONTAL
        dayRow.gravity = Gravity.CENTER_HORIZONTAL
        val dayViews = ArrayList<TextView>()

        fun refreshDays() {
            for (i in DAY_BITS.indices) {
                val on = ((days shr DAY_BITS[i]) and 1) == 1
                val d = dayViews[i]
                d.background = roundedDrawable(if (on) C.ACCENT else C.SURFACE2, dp(20))
                d.setTextColor(if (on) C.INK else C.MUTED)
            }
        }

        for (i in DAY_BITS.indices) {
            val d = text(DAY_LETTERS[i], 14f, C.MUTED, bold = true, center = true)
            d.pressable(0.9f)
            d.setOnClickListener {
                days = days xor (1 shl DAY_BITS[i])
                refreshDays()
                d.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
            val p = LinearLayout.LayoutParams(dp(40), dp(40))
            p.leftMargin = dp(3)
            p.rightMargin = dp(3)
            dayRow.addView(d, p)
            dayViews.add(d)
        }
        refreshDays()
        box.addView(dayRow, lp(10))

        val quickDays = LinearLayout(this)
        quickDays.orientation = LinearLayout.HORIZONTAL
        quickDays.gravity = Gravity.CENTER_HORIZONTAL
        val presets = listOf("Every day" to 127, "Weekdays" to 62, "Weekends" to 65)
        for ((name, mask) in presets) {
            val t = text(name, 13f, C.ACCENT, bold = true)
            t.setPadding(dp(12), dp(8), dp(12), dp(8))
            t.pressable(0.94f)
            t.setOnClickListener {
                days = mask
                refreshDays()
            }
            quickDays.addView(t, LinearLayout.LayoutParams(WRAP, WRAP))
        }
        box.addView(quickDays, lp(4))

        val err = text("", 13f, C.DANGER, center = true)
        box.addView(err, lp(6))

        val save = primaryButton("Save routine")
        save.setOnClickListener {
            if (start == end) {
                err.text = "Start and end cannot be the same."
                return@setOnClickListener
            }
            if (days == 0) {
                err.text = "Pick at least one day."
                return@setOnClickListener
            }
            val nm = nameEdit.text.toString().trim().ifEmpty { "Focus block" }
            val r = Routine(existing?.id ?: System.currentTimeMillis(), nm, start, end, days, existing?.enabled ?: true)
            val updated = routines.filter { it.id != r.id }.toMutableList()
            updated.add(r)
            commit(updated, after = { closeSheet(dlg, sheet) }, onCancel = {})
        }
        box.addView(save, lp(12))
        val cancel = secondaryButton("Cancel")
        cancel.setOnClickListener { closeSheet(dlg, sheet) }
        box.addView(cancel, lp(10))

        refresh()
        showSheet(dlg, sheet)
    }

    // ------------------------------------------------------------------ bottom sheet

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
        handle.background = roundedDrawable(C.LINE, dp(3))
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
            setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
            )
        }
        return Pair(dlg, sheet)
    }

    private fun showSheet(dlg: Dialog, sheet: View) {
        sheet.translationY = dp(800).toFloat()
        dlg.show()
        sheet.animate().translationY(0f).setDuration(280L).setInterpolator(DecelerateInterpolator(2f)).start()
    }

    private fun closeSheet(dlg: Dialog, sheet: View) {
        sheet.animate().translationY(sheet.height.toFloat()).setDuration(180L)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { if (dlg.isShowing) dlg.dismiss() }
            .start()
    }

    // ------------------------------------------------------------------ small helpers

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun lp(top: Int): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(MATCH, WRAP)
        p.topMargin = dp(top)
        return p
    }

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
        center: Boolean = false
    ): TextView {
        val t = TextView(this)
        t.text = s
        t.textSize = sp
        t.setTextColor(color)
        if (bold) t.typeface = Typeface.DEFAULT_BOLD
        if (center) t.gravity = Gravity.CENTER
        return t
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

    private fun styleSwitch(s: Switch) {
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        s.thumbTintList = ColorStateList(states, intArrayOf(C.ACCENT, C.MUTED))
        s.trackTintList = ColorStateList(states, intArrayOf(Color.argb(90, 60, 216, 236), C.SURFACE2))
    }

    private fun primaryButton(label: String): android.widget.Button {
        val b = android.widget.Button(this)
        b.text = label
        b.isAllCaps = false
        b.setTextColor(C.INK)
        b.textSize = 17f
        b.typeface = Typeface.DEFAULT_BOLD
        b.minHeight = dp(56)
        b.minimumHeight = dp(56)
        b.stateListAnimator = null
        b.background = withRipple(gradientDrawable(C.ACCENT_HI, C.ACCENT, dp(18)), dp(18), Color.argb(50, 0, 0, 0))
        b.pressable()
        return b
    }

    private fun secondaryButton(label: String): android.widget.Button {
        val b = android.widget.Button(this)
        b.text = label
        b.isAllCaps = false
        b.setTextColor(C.FG)
        b.textSize = 16f
        b.minHeight = dp(50)
        b.minimumHeight = dp(50)
        b.stateListAnimator = null
        b.background = withRipple(roundedDrawable(C.SURFACE2, dp(18)), dp(18))
        b.pressable()
        return b
    }
}
