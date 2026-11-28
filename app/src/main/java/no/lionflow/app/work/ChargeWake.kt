package no.lionflow.app.work

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import no.lionflow.app.LionFlowApp
import no.lionflow.app.R
import no.lionflow.app.model.VehicleStatus
import java.util.Calendar

/**
 * EV Remote (PLAN.md item 2): while the car charges it sends nothing by itself (plugged in 20:17 at 29 %, next report
 * 05:57 at 78 %), so the background check only saw old data and the charge limit couldn't work.
 *
 *  - Plugged in with a scheduled start (nextDelayedTime): one exact wake ~3 min after that time, nothing before.
 *  - Charging: wake the car, then read the status, every ~15 min; every ~5 min when within 8 % of the charge limit.
 *  - Finished, stopped or unplugged: no more wakes. An unplugged car is never woken here (weak 12 V battery);
 *    while charging the 12 V battery is fed by the DC/DC converter.
 *  - 3 unanswered wake-ups in a row: stop and tell the user (the 12 V battery may be low).
 *
 * Exact alarms (setExactAndAllowWhileIdle) start an expedited worker, so Android's Doze doesn't hold them back at night.
 */
object ChargeWake {
    private const val REQ = 4711
    private const val NOTE_ID = 31
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("charge_wake", Context.MODE_PRIVATE)

    private fun log(app: LionFlowApp, line: String) = try { app.remote.addLog("charge wake: $line") } catch (_: Exception) { }

    /** Call after every fresh car status, from anywhere. Decides whether (and when) the next wake-up is. */
    fun plan(app: LionFlowApp, st: VehicleStatus) {
        if (st.energyType == "Fuel" || st.isMildHybrid) return
        if (!app.remote.isSetUp) { stop(app, null); return }
        val s = app.settings.value
        val now = System.currentTimeMillis()
        when {
            !st.plugged -> stop(app, "unplugged")
            // Charging for pre-heating on grid power: GridPreheat wakes the car itself.
            st.isCharging && GridPreheat.ownActive(app) -> {}
            st.isCharging -> {
                val near = s.chargeLimitOn && (st.batteryPct ?: 0.0) >= s.chargeLimitPct - 8
                at(app, now + (if (near) 5 else 15) * 60_000L, if (near) "charging, near the limit" else "charging")
            }
            st.chargingStatus == "Finished" -> stop(app, "charging finished")
            // After "Turn off" the car still reports a time: ChargePower.scheduledStart leaves it out (upstream 4eb9770).
            no.lionflow.app.model.ChargePower.scheduledStart(st) != null -> no.lionflow.app.model.ChargePower.scheduledStart(st)!!.let { (h, m) -> at(app, nextTime(h, m) + 3 * 60_000L, "scheduled start") }
            else -> stop(app, "not charging (${st.chargingStatus ?: "?"})")
        }
    }

    /** The next time the clock shows [h]:[m] (today or tomorrow). */
    private fun nextTime(h: Int, m: Int): Long {
        val c = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        if (c.timeInMillis <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    private fun pending(ctx: Context): PendingIntent = PendingIntent.getBroadcast(ctx, REQ,
        Intent(ctx, ChargeWakeReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Sets the alarm for [t], unless one already set comes sooner (status reads in between mustn't push it back). */
    private fun at(app: LionFlowApp, t: Long, why: String) {
        val p = prefs(app)
        val now = System.currentTimeMillis()
        val existing = p.getLong("nextAt", 0L)
        if (existing in (now + 1)..t) return
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        try {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pending(app))
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pending(app))
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pending(app))
        }
        p.edit().putLong("nextAt", t).putBoolean("active", true).apply()
        val hm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(t))
        log(app, "next wake-up $hm ($why)" + if (exact) "" else " – exact alarms not allowed, Android may delay it")
    }

    /** No more wake-ups. [why] null = quietly (nothing to log). */
    fun stop(app: LionFlowApp, why: String?) {
        val p = prefs(app)
        val wasActive = p.getBoolean("active", false)
        (app.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending(app))
        p.edit().putLong("nextAt", 0L).putBoolean("active", false).putInt("fails", 0).apply()
        if (wasActive && why != null) log(app, "stopped ($why)")
    }

    /** The alarm went off: wake the car, read the status, check the charge limit, plan the next one. */
    internal suspend fun run(app: LionFlowApp) {
        val p = prefs(app)
        p.edit().putLong("nextAt", 0L).apply()
        if (!app.api.isSignedIn || !app.remote.isSetUp) { stop(app, "remote control not set up"); return }
        val vehicle = app.api.cachedVehicle() ?: return
        val answered = Waker.wakeAndWait(app)
        if (answered == false) {
            val fails = p.getInt("fails", 0) + 1
            p.edit().putInt("fails", fails).apply()
            log(app, "the car didn't answer the wake-up ($fails in a row)")
            if (fails >= 3) {
                stop(app, "3 wake-ups without an answer")
                notify(app, "Car isn't answering", "It didn't answer 3 wake-ups while charging, so EV Remote stopped waking it. Its 12 V battery may be low.")
                try { app.remote.disconnect() } catch (_: Exception) { }
                return
            }
        } else if (answered == true) p.edit().putInt("fails", 0).apply()
        val st = try { no.lionflow.app.model.ChargePower.record(app.api.readStatus(vehicle)) } catch (e: Exception) {
            log(app, "status read failed (${e.javaClass.simpleName})"); null
        }
        try { app.remote.disconnect() } catch (_: Exception) { }
        if (st == null) { at(app, System.currentTimeMillis() + 15 * 60_000L, "retry after a failed read"); return }
        no.lionflow.app.widget.WidgetData.save(app, st)
        log(app, "${if (answered == true) "car answered" else "no answer"} · ${st.batteryPct?.toInt() ?: "?"} % · ${st.chargingStatus ?: "?"}" +
            if (st.plugged) "" else " · unplugged")
        // The charge limit (stops charging at the set %), as in the regular background check.
        if (!st.preconditioningOn) try {
            ChargeLimit.check(app, st)?.let { notify(app, "Charge limit", it) }
        } catch (_: Exception) { }
        // Unanswered: the status may be old, so keep trying on the same rhythm instead of trusting it – only while it
        // still says charging (seen 2026-11-28: stopped at 85 %, yet woken 3 more times).
        if (answered == false && st.plugged && st.isCharging) at(app, System.currentTimeMillis() + 15 * 60_000L, "retry, the car didn't answer")
        else plan(app, st)
    }

    internal fun notify(ctx: Context, title: String, text: String, id: Int = NOTE_ID) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val n = NotificationCompat.Builder(ctx, LionFlowApp.CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(no.lionflow.app.ui.l10n.tr(title)).setContentText(no.lionflow.app.ui.l10n.tr(text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(no.lionflow.app.ui.l10n.tr(text))).setAutoCancel(true).build()
        try { NotificationManagerCompat.from(ctx).notify(id, n) } catch (_: SecurityException) { }
    }
}

/** The exact alarm: hands over to an expedited worker (a receiver may only run for a few seconds). */
class ChargeWakeReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        androidx.work.WorkManager.getInstance(ctx).enqueueUniqueWork(
            "charge_wake", androidx.work.ExistingWorkPolicy.REPLACE,
            androidx.work.OneTimeWorkRequestBuilder<ChargeWakeWorker>()
                .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
                .build())
    }
}

class ChargeWakeWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        try { ChargeWake.run(applicationContext as LionFlowApp) } catch (_: Exception) { }
        return Result.success()
    }

    /** Android 8–11 run expedited work as a short foreground service, which needs a notification. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val ctx = applicationContext
        val n = NotificationCompat.Builder(ctx, LionFlowApp.CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("EV Remote").setContentText(no.lionflow.app.ui.l10n.tr("Checking the charging car"))
            .setPriority(NotificationCompat.PRIORITY_LOW).setSilent(true).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(32, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(32, n)
    }
}
