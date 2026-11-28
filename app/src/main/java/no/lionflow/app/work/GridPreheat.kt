package no.lionflow.app.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import no.lionflow.app.LionFlowApp
import no.lionflow.app.R
import no.lionflow.app.api.CommandResult
import no.lionflow.app.model.Program
import no.lionflow.app.model.VehicleStatus
import java.util.Calendar

/**
 * EV Remote: pre-heating on grid power. A plugged-in car that isn't charging (finished, or stopped at the charge limit
 * and waiting for its delayed start) heats from its battery. So charging is switched on while it pre-heats and back to
 * the delayed start afterwards:
 *  - pre-heating schedules (the car's own): charging from [LEAD_MIN] min before the set time; checks from the set time;
 *  - "Start pre-heating now": the app asks first, then charging; checks from [CHECK_MIN] min on.
 * Every [CHECK_MIN] min the car is woken (plugged in and charging, so its 12 V battery is fed) and read; once it reports
 * pre-heating off (in a report from after the start), charging goes back to the delayed start and checking ends.
 * At the latest after [MAX_NOW_MIN] min (now) or [MAX_AFTER_MIN] min after the set time. The charge limit allows
 * [OVER_LIMIT] % more meanwhile. An unplugged car is never woken: checks first read the server's status.
 */
object GridPreheat {
    const val LEAD_MIN = 30
    const val CHECK_MIN = 5
    const val MAX_NOW_MIN = 60
    const val MAX_AFTER_MIN = 30
    const val OVER_LIMIT = 5
    private const val REQ_START = 4721
    private const val REQ_END = 4722
    private const val K_SCHEDULES = "gridPreheatSchedules"   // "0" = off; on by default
    private val DAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("grid_preheat", Context.MODE_PRIVATE)
    private fun log(app: LionFlowApp, line: String) = try { app.remote.addLog("grid pre-heat: $line") } catch (_: Exception) { }
    private fun hm(t: Long) = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(t))

    fun forSchedules(app: LionFlowApp): Boolean = app.settings.raw(K_SCHEDULES) != "0"
    fun setForSchedules(app: LionFlowApp, on: Boolean) {
        app.settings.setRaw(K_SCHEDULES, if (on) "1" else "0")
        if (!on) cancel(app, REQ_START).also { prefs(app).edit().putLong("startAt", 0L).apply() }
    }

    /** Plugged in but not charging: pre-heating now would use the battery. */
    fun wouldUseBattery(st: VehicleStatus): Boolean =
        st.plugged && !st.isCharging && st.energyType != "Fuel" && !st.isMildHybrid

    /** Charging is on for pre-heating (the charge limit allows [OVER_LIMIT] % more). */
    fun active(ctx: Context): Boolean {
        val p = prefs(ctx)
        return p.getBoolean("active", false) && System.currentTimeMillis() < p.getLong("hardEnd", 0L) + 30 * 60_000L
    }

    /** EV Remote switched charging on itself: GridPreheat wakes the car, so ChargeWake doesn't need to. */
    fun ownActive(ctx: Context): Boolean = active(ctx) && prefs(ctx).getBoolean("own", false)

    // ---- alarms ----

    private fun pending(ctx: Context, req: Int): PendingIntent = PendingIntent.getBroadcast(ctx, req,
        Intent(ctx, GridPreheatReceiver::class.java).putExtra("what", if (req == REQ_START) "start" else "end"),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun alarm(app: LionFlowApp, req: Int, t: Long) {
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pending(app, req))
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pending(app, req))
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pending(app, req))
        }
    }

    private fun cancel(app: LionFlowApp, req: Int) =
        (app.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending(app, req))

    /** The next time an enabled schedule is set for whose charging start ([LEAD_MIN] before) is still ahead. */
    private fun nextSchedule(programs: List<Program>): Long? {
        val now = System.currentTimeMillis()
        var best: Long? = null
        for (d in 0..7) for (pr in programs.filter { it.enabled && it.days.isNotEmpty() }) {
            val c = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, d)
                set(Calendar.HOUR_OF_DAY, pr.hour); set(Calendar.MINUTE, pr.minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            if (DAYS[(c.get(Calendar.DAY_OF_WEEK) + 5) % 7] !in pr.days) continue
            val t = c.timeInMillis
            if (t - LEAD_MIN * 60_000L > now && (best == null || t < best)) best = t
        }
        return best
    }

    /** Call after every fresh car status: sets (or moves) the alarm for the next pre-heating schedule. */
    fun plan(app: LionFlowApp, st: VehicleStatus) {
        if (st.energyType == "Fuel" || st.isMildHybrid) return
        val p = prefs(app)
        val next = if (forSchedules(app) && app.remote.isSetUp) nextSchedule(st.programs) else null
        if (next == null) {
            if (p.getLong("startAt", 0L) != 0L) { cancel(app, REQ_START); p.edit().putLong("startAt", 0L).putLong("schedAt", 0L).apply() }
            return
        }
        if (p.getLong("schedAt", 0L) == next) return
        val startAt = next - LEAD_MIN * 60_000L
        alarm(app, REQ_START, startAt)
        p.edit().putLong("startAt", startAt).putLong("schedAt", next).apply()
        log(app, "next schedule ${hm(next)}: charging from ${hm(startAt)} if plugged in and not charging")
    }

    // ---- charging on / off ----

    /** Charging on until pre-heating ends: first check at [firstCheck], at the latest off at [hardEnd]. */
    suspend fun startCharging(app: LionFlowApp, st: VehicleStatus, firstCheck: Long, hardEnd: Long, why: String): CommandResult {
        val (h, m) = ChargeLimit.delayTime(st)
        val r = app.remote.charging(true, h, m)
        if (r is CommandResult.Failed) { log(app, "charging on ($why) → ${r.reason}"); return r }
        prefs(app).edit().putBoolean("active", true).putBoolean("own", true).putLong("startedAt", System.currentTimeMillis())
            .putLong("hardEnd", hardEnd).putInt("h", h).putInt("m", m).apply()
        alarm(app, REQ_END, firstCheck)
        log(app, "charging on ($why) until pre-heating ends, checked every $CHECK_MIN min from ${hm(firstCheck)}, " +
            "at the latest ${hm(hardEnd)}; delayed start %02d:%02d afterwards".format(h, m))
        return r
    }

    /** "Start pre-heating now" on grid power. */
    suspend fun startChargingNow(app: LionFlowApp, st: VehicleStatus): CommandResult {
        val now = System.currentTimeMillis()
        return startCharging(app, st, now + CHECK_MIN * 60_000L, now + MAX_NOW_MIN * 60_000L, "pre-heat now")
    }

    /** Back to the delayed start (charging off), if EV Remote switched it on. */
    suspend fun stopCharging(app: LionFlowApp, why: String) {
        val p = prefs(app)
        if (!p.getBoolean("active", false)) return
        val own = p.getBoolean("own", false)
        val h = p.getInt("h", 3); val m = p.getInt("m", 0)
        p.edit().putBoolean("active", false).apply()
        cancel(app, REQ_END)
        if (!own) { log(app, "ended ($why)"); return }
        val r = app.remote.charging(false, h, m)
        log(app, "charging off ($why), delayed start %02d:%02d → ${if (r is CommandResult.Failed) r.reason else "sent"}".format(h, m))
    }

    /** Schedule alarm: charging on if the car is plugged in and not charging (only the server's status is read). */
    internal suspend fun onStart(app: LionFlowApp) {
        val p = prefs(app)
        val sched = p.getLong("schedAt", 0L)
        p.edit().putLong("startAt", 0L).putLong("schedAt", 0L).apply()
        if (!forSchedules(app) || !app.api.isSignedIn || !app.remote.isSetUp) return
        val vehicle = app.api.cachedVehicle() ?: return
        val st = try { no.lionflow.app.model.ChargePower.record(app.api.readStatus(vehicle)) } catch (e: Exception) {
            log(app, "status read failed (${e.javaClass.simpleName})"); null
        } ?: return
        try {
            val set = if (sched > 0) sched else System.currentTimeMillis() + LEAD_MIN * 60_000L
            val hardEnd = set + MAX_AFTER_MIN * 60_000L
            when {
                !st.plugged -> log(app, "schedule ${hm(set)}: not plugged in, pre-heats from the battery")
                st.isCharging -> {
                    // Charging anyway: only the charge limit's margin, until the latest end (no wake-ups of its own).
                    prefs(app).edit().putBoolean("active", true).putBoolean("own", false).putLong("startedAt", System.currentTimeMillis())
                        .putLong("hardEnd", hardEnd).apply()
                    alarm(app, REQ_END, hardEnd)
                    log(app, "schedule ${hm(set)}: already charging, the charge limit allows $OVER_LIMIT % more until ${hm(hardEnd)}")
                }
                // Pre-heating may already run before the set time, so the checks start at it.
                else -> startCharging(app, st, set, hardEnd, "schedule ${hm(set)}")
            }
        } finally {
            try { app.remote.disconnect() } catch (_: Exception) { }
            plan(app, st)
        }
    }

    /**
     * Check alarm: wake the car (only if the server still says plugged in), read it; pre-heating reported off in a report
     * from after the start → charging off and no more checks; else the next check in [CHECK_MIN] min, up to the latest end.
     */
    internal suspend fun onEnd(app: LionFlowApp) {
        val p = prefs(app)
        if (!p.getBoolean("active", false)) return
        val now = System.currentTimeMillis()
        val hardEnd = p.getLong("hardEnd", 0L)
        try {
            if (!p.getBoolean("own", false)) { stopCharging(app, "schedule over"); return }
            if (now >= hardEnd - 30_000L) { stopCharging(app, "time limit reached"); return }
            val vehicle = app.api.cachedVehicle()
            suspend fun read() = vehicle?.let { v -> try { no.lionflow.app.model.ChargePower.record(app.api.readStatus(v)) } catch (_: Exception) { null } }
            var st = read()
            if (st != null && !st.plugged) { p.edit().putBoolean("active", false).apply(); log(app, "ended (unplugged)"); return }
            val answered = if (vehicle != null) Waker.wakeAndWait(app) else null
            st = read() ?: st
            if (st != null && !st.plugged) { p.edit().putBoolean("active", false).apply(); log(app, "ended (unplugged)"); return }
            val fresh = st?.updatedAtMillis?.let { it >= p.getLong("startedAt", 0L) + 60_000L } == true
            if (st != null && fresh && !st.preconditioningOn) {
                stopCharging(app, "pre-heating ended")
                if (app.settings.value.notifyPreheat) ChargeWake.notify(app, "Pre-heating finished", "Your car should be warm and ready.", 3)
                return
            }
            val next = minOf(System.currentTimeMillis() + CHECK_MIN * 60_000L, hardEnd)
            alarm(app, REQ_END, next)
            log(app, (if (!fresh) "no new report from the car" + (if (answered == false) " (no answer)" else "") else "still pre-heating") +
                ", next check ${hm(next)}")
        } finally {
            try { app.remote.disconnect() } catch (_: Exception) { }
        }
    }
}

/** The exact alarms: hand over to an expedited worker (a receiver may only run for a few seconds). */
class GridPreheatReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val what = intent.getStringExtra("what") ?: return
        androidx.work.WorkManager.getInstance(ctx).enqueueUniqueWork(
            "grid_preheat_$what", androidx.work.ExistingWorkPolicy.REPLACE,
            androidx.work.OneTimeWorkRequestBuilder<GridPreheatWorker>()
                .setInputData(androidx.work.workDataOf("what" to what))
                .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
                .build())
    }
}

class GridPreheatWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as LionFlowApp
        try {
            if (inputData.getString("what") == "start") GridPreheat.onStart(app) else GridPreheat.onEnd(app)
        } catch (_: Exception) { }
        return Result.success()
    }

    /** Android 8–11 run expedited work as a short foreground service, which needs a notification. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val ctx = applicationContext
        val n = NotificationCompat.Builder(ctx, LionFlowApp.CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("EV Remote").setContentText(no.lionflow.app.ui.l10n.tr("Pre-heating on grid power"))
            .setPriority(NotificationCompat.PRIORITY_LOW).setSilent(true).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(33, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(33, n)
    }
}
