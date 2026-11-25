package com.focuslock.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.format.DateFormat
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * One automatic lock: starts at [startMin] and stops at [endMin] on the chosen days.
 * Times are minutes after midnight. An end time before the start means the lock runs into the next day.
 * [days] is a bit mask: bit 0 = Sunday, bit 1 = Monday ... bit 6 = Saturday.
 */
class Routine(
    val id: Long,
    var name: String,
    var startMin: Int,
    var endMin: Int,
    var days: Int,
    var enabled: Boolean
) {
    val lengthMin: Int get() = if (endMin > startMin) endMin - startMin else endMin + 1440 - startMin
    val overnight: Boolean get() = endMin <= startMin

    fun copy(): Routine = Routine(id, name, startMin, endMin, days, enabled)
}

/** A routine that should be locking the phone right now. */
class Due(val start: Long, val end: Long, val name: String)

/** The next time a routine will start. */
class Next(val routine: Routine, val at: Long)

object Routines {

    const val ACTION_ALARM = "com.focuslock.app.ROUTINE_ALARM"
    private const val PREFS = "fortis_routines"
    private const val REQ = 7001
    private const val MIN = 60_000L

    // ------------------------------------------------------------------ storage

    fun load(ctx: Context): MutableList<Routine> {
        val raw = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("list", "[]") ?: "[]"
        val out = ArrayList<Routine>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Routine(
                        o.getLong("id"),
                        o.optString("name", "Focus block"),
                        o.getInt("start"),
                        o.getInt("end"),
                        o.optInt("days", 127),
                        o.optBoolean("on", true)
                    )
                )
            }
        } catch (_: Exception) {
        }
        return out
    }

    /** Saves the list and sets the next wake-up alarm. */
    fun save(ctx: Context, list: List<Routine>) {
        val arr = JSONArray()
        for (r in list) {
            arr.put(
                JSONObject()
                    .put("id", r.id)
                    .put("name", r.name)
                    .put("start", r.startMin)
                    .put("end", r.endMin)
                    .put("days", r.days)
                    .put("on", r.enabled)
            )
        }
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("list", arr.toString()).apply()
        reschedule(ctx)
    }

    fun isMasterOn(ctx: Context): Boolean =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("master", true)

    fun setMasterOn(ctx: Context, on: Boolean) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean("master", on).apply()
        reschedule(ctx)
    }

    // ------------------------------------------------------------------ schedule math

    private fun dayStart(base: Long, addDays: Int): Calendar {
        val c = Calendar.getInstance()
        c.timeInMillis = base
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        c.add(Calendar.DAY_OF_YEAR, addDays)
        return c
    }

    private fun at(day: Calendar, minutes: Int): Long {
        val c = day.clone() as Calendar
        c.set(Calendar.HOUR_OF_DAY, minutes / 60)
        c.set(Calendar.MINUTE, minutes % 60)
        return c.timeInMillis
    }

    private fun dayOn(r: Routine, day: Calendar): Boolean =
        ((r.days shr (day.get(Calendar.DAY_OF_WEEK) - 1)) and 1) == 1

    /** If [now] is inside one of this routine's windows, returns when that window ends. */
    fun activeEnd(r: Routine, now: Long): Long? {
        for (off in -1..0) {
            val day = dayStart(now, off)
            if (!dayOn(r, day)) continue
            val start = at(day, r.startMin)
            val endDay = if (r.overnight) dayStart(now, off + 1) else day
            val end = at(endDay, r.endMin)
            if (now >= start - 3000L && now < end) return end
        }
        return null
    }

    /** The next time this routine starts, after [now]. */
    fun nextStart(r: Routine, now: Long): Long? {
        for (off in 0..7) {
            val day = dayStart(now, off)
            if (!dayOn(r, day)) continue
            val start = at(day, r.startMin)
            if (start > now + 5000L) return start
        }
        return null
    }

    /** The routine (if any) that should be locking right now. The one that ends last wins. */
    fun due(ctx: Context, list: List<Routine>, now: Long): Due? {
        if (!isMasterOn(ctx)) return null
        var best: Due? = null
        for (r in list) {
            if (!r.enabled) continue
            val e = activeEnd(r, now) ?: continue
            val b = best
            if (b == null || e > b.end) best = Due(e - r.lengthMin * MIN, e, r.name)
        }
        return best
    }

    fun nextUp(list: List<Routine>, now: Long): Next? {
        var best: Next? = null
        for (r in list) {
            if (!r.enabled) continue
            val n = nextStart(r, now) ?: continue
            val b = best
            if (b == null || n < b.at) best = Next(r, n)
        }
        return best
    }

    // ------------------------------------------------------------------ starting locks

    /** True when the lock permission (the accessibility service) is switched on. */
    fun permissionOn(ctx: Context): Boolean {
        val enabled = Settings.Secure.getString(
            ctx.applicationContext.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val target = ComponentName(ctx.applicationContext, LockAccessibilityService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == target }
    }

    /**
     * Starts a lock if a routine is due and nothing is locked yet.
     * Safe to call as often as you like. Returns true if it started a lock.
     */
    fun reconcile(ctx: Context, kick: Boolean = true): Boolean {
        val app = ctx.applicationContext
        LockAccessibilityService.init(app)
        if (LockAccessibilityService.isLocked()) return false
        // Without the lock permission a lock could not be enforced, so do not pretend to lock.
        if (!permissionOn(app)) return false
        val now = System.currentTimeMillis()
        val d = due(app, load(app), now) ?: return false
        if (d.end <= now) return false
        app.getSharedPreferences("focuslock_ui", Context.MODE_PRIVATE).edit()
            .putLong("lockStart", d.start)
            .putString("lockLabel", d.name)
            .apply()
        LockAccessibilityService.lockEndTime = d.end
        try {
            app.startForegroundService(Intent(app, LockService::class.java))
        } catch (_: Exception) {
            // Android may refuse to start it from the background. The lock still works without it.
        }
        if (kick) LockAccessibilityService.requestKick()
        return true
    }

    // ------------------------------------------------------------------ alarms

    /** Sets one alarm for the earliest upcoming start. It wakes the phone even when the app is closed. */
    fun reschedule(ctx: Context) {
        val app = ctx.applicationContext
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            app, REQ,
            Intent(app, RoutineReceiver::class.java).setAction(ACTION_ALARM),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.cancel(pi)
        if (!isMasterOn(app)) return
        val n = nextUp(load(app), System.currentTimeMillis()) ?: return
        val show = PendingIntent.getActivity(
            app, REQ + 1,
            Intent(app, RoutinesActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(n.at, show), pi)
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------ labels

    fun timeLabel(ctx: Context, min: Int): String {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, min / 60)
        c.set(Calendar.MINUTE, min % 60)
        c.set(Calendar.SECOND, 0)
        return DateFormat.getTimeFormat(ctx).format(c.time)
    }

    fun clockLabel(ctx: Context, ts: Long): String = DateFormat.getTimeFormat(ctx).format(Date(ts))

    /** "today at 8:00 AM", "tomorrow at 8:00 AM" or "Mon at 8:00 AM". */
    fun whenLabel(ctx: Context, ts: Long): String {
        val a = dayStart(System.currentTimeMillis(), 0).timeInMillis
        val b = dayStart(ts, 0).timeInMillis
        val days = Math.round((b - a) / 86_400_000.0).toInt()
        val word = when (days) {
            0 -> "today"
            1 -> "tomorrow"
            else -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(ts))
        }
        return word + " at " + clockLabel(ctx, ts)
    }

    fun lengthLabel(min: Int): String {
        val h = min / 60
        val m = min % 60
        return when {
            h > 0 && m > 0 -> "$h h $m min"
            h > 0 -> "$h h"
            else -> "$m min"
        }
    }

    /** "in 2 h 15 min" */
    fun inLabel(ms: Long): String {
        val m = (ms + MIN - 1L) / MIN
        return when {
            m <= 0L -> "any moment now"
            m < 60L -> "in $m min"
            m < 1440L -> if (m % 60L == 0L) "in ${m / 60L} h" else "in ${m / 60L} h ${m % 60L} min"
            else -> "in ${m / 1440L} d ${(m % 1440L) / 60L} h"
        }
    }

    /** Short line for the main screen. */
    fun summary(ctx: Context): String {
        val list = load(ctx)
        if (list.isEmpty()) return "Lock automatically at set times"
        if (!isMasterOn(ctx)) return "Paused"
        val now = System.currentTimeMillis()
        val d = due(ctx, list, now)
        if (d != null) return "Now: " + d.name + ", until " + clockLabel(ctx, d.end)
        val n = nextUp(list, now) ?: return "No routine is switched on"
        return "Next: " + n.routine.name + ", " + whenLabel(ctx, n.at)
    }
}

/** Wakes up for the routine alarm and after a restart, time change or app update. */
class RoutineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Routines.reconcile(context)
        Routines.reschedule(context)
    }
}
