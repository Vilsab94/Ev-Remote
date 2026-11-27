package no.lionflow.app.model

import android.content.Context
import android.content.SharedPreferences
import no.lionflow.app.api.objects
import no.lionflow.app.data.SettingsStore
import org.json.JSONArray
import java.util.Locale

/**
 * Charging power in kW. The car only reports "km of range per hour", so LionFlow works it out:
 *  - measured: energy in the battery between two car reports (4–40 min apart) ÷ time between them
 *  - estimated (until there are two reports): energy still needed ÷ time left reported by the car
 */
object ChargePower {
    /** Minutes left and the % they lead to: to LionFlow's limit when one is set and still ahead, otherwise the car's own time to full. */
    fun timeLeft(st: VehicleStatus): Pair<Int, Int?>? {
        val rem = st.remainingMinutes ?: return null
        val s = settings?.value
        val soc = st.batteryPct
        if (s != null && s.chargeLimitOn && soc != null && soc < s.chargeLimitPct && soc < 100)
            return Pair((rem * (s.chargeLimitPct - soc) / (100 - soc)).toInt().coerceAtLeast(1), s.chargeLimitPct)
        return Pair(rem, null)
    }
    fun leftText(st: VehicleStatus): String? = timeLeft(st)?.let { (m, pct) ->
        " · ${m / 60} h ${m % 60} min left" + (pct?.let { " to $it %" } ?: "")
    }

    data class Reading(val kw: Double, val measured: Boolean)

    private var prefs: SharedPreferences? = null
    private var settings: SettingsStore? = null

    private var vin = ""
    private fun k(name: String) = if (vin.isEmpty()) name else "${name}_$vin"

    fun init(ctx: Context, s: SettingsStore, currentVin: String?) {
        prefs = ctx.getSharedPreferences("charge_samples", Context.MODE_PRIVATE)
        settings = s
        vin = currentVin.orEmpty()
        val p = prefs!!
        // EV Remote: charging samples and report history saved with earlier capacity maths (server figures, 43 kWh)
        // would mix two capacities in kW and in the log; drop them once.
        if (!p.getBoolean("evCapV3", false)) {
            val e = p.edit()
            // Also the car-trip snapshots (energy at the start of a drive in the old maths).
            p.all.keys.filter { key -> listOf("s", "hist", "ctLast", "ctOpen").any { key == it || key.startsWith(it + "_") } }.forEach { e.remove(it) }
            e.putBoolean("evCapV3", true).apply()
        }
        if (vin.isNotEmpty() && !p.contains(k("kmPerPct")) && p.contains("kmPerPct")) p.edit().putFloat(k("kmPerPct"), p.getFloat(k("kmPerPct"), 0f)).remove("kmPerPct").apply()
    }

    fun setVin(v: String) { vin = v }
    internal fun store(): SharedPreferences? = prefs
    internal fun key(name: String) = k(name)

    /** EV Remote: usable capacity of the new battery from Settings (default 46 kWh), or null when none is set. */
    fun newKwh(): Double? = settings?.value?.batteryKwh?.takeIf { it > 0 }

    /**
     * EV Remote: the last SoH the car sent (health.capacity), kept for reports without it; null if never received.
     * Kept per car and also once for any car: the per-car key depends on the VIN being known, which it isn't yet
     * on some background starts, and then the same car would get 100 % (46 kWh) one time and 93 % (43 kWh) the next.
     */
    fun sohPct(): Double? = prefs?.let { p ->
        p.getFloat(k("soh"), 0f).takeIf { it > 0 } ?: p.getFloat("soh_any", 0f).takeIf { it > 0 }
    }?.toDouble()

    /** EV Remote: when the car last sent its SoH (the report's time), or null if never. */
    fun sohAtMillis(): Long? = prefs?.let { p ->
        p.getLong(k("sohAt"), 0L).takeIf { it > 0 } ?: p.getLong("sohAt_any", 0L).takeIf { it > 0 }
    }

    /** EV Remote: which health field the saved SoH came from ("capacity" or "resistance"), or null if never. */
    fun sohSource(): String? = prefs?.let { p ->
        p.getString(k("sohSrc"), null)?.takeIf { it.isNotEmpty() } ?: p.getString("sohSrc_any", null)?.takeIf { it.isNotEmpty() }
    }

    /** EV Remote: usable capacity now = new battery × SoH (100 % until the car has sent one). */
    fun userKwh(): Double? = newKwh()?.let { it * (sohPct() ?: 100.0) / 100.0 }

    /** EV Remote: a higher SoH than the saved one is only taken once the saved one is this old. */
    private const val SOH_KEEP_MS = 60L * 86_400_000L
    private fun fmtPct(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else String.format(Locale.US, "%.1f", v)

    /** EV Remote: the command & error log (set in LionFlowApp). */
    @Volatile var log: ((String) -> Unit)? = null

    /**
     * EV Remote: the server's battery figures are wrong for some cars (Mokka-e: capacity 32.96 kWh, real ~43 kWh),
     * while the level % is right. The energy in the battery becomes level % × [userKwh], so charging kW, time to
     * the limit, the charge log and trips logged from the car all use it. The server's capacity is remembered to
     * correct the trip energy the server reports (see [tripFactor]).
     */
    /** EV Remote: [applyBatterySize] for a status that is not passed through [record] (e.g. parsed again from raw). */
    fun adjusted(st: VehicleStatus): VehicleStatus = applyBatterySize(st)

    private fun applyBatterySize(st: VehicleStatus): VehicleStatus {
        if (st.energyType != "Electric") return st
        val p = prefs
        // Only a SoH that is really in this report is saved (with the report's time), not one filled in from before.
        // The Mokka-e sends health.resistance only, sometimes 100 (not worked out yet) and sometimes 93 (the real one,
        // confirmed with OBD). A higher value is only taken when the saved one is older than 60 days, so the usable
        // capacity (new × SoH) doesn't jump between 42.8 and 46 kWh.
        VehicleStatus.sohSourceOf(st.raw)?.let { (soh, src) ->
            val at = st.updatedAtMillis ?: System.currentTimeMillis()
            val kept = sohPct()
            val keptAt = sohAtMillis() ?: 0L
            val take = kept == null || soh <= kept || at - keptAt > SOH_KEEP_MS
            if (!take) {
                if (p != null && p.getLong(k("sohIgnoredAt"), 0L) != at) {
                    p.edit().putLong(k("sohIgnoredAt"), at).apply()
                    try { log?.invoke("health ignored: ${fmtPct(soh)} (kept ${fmtPct(kept!!)})") } catch (_: Exception) { }
                }
            } else if (p != null && (p.getFloat(k("soh"), 0f) != soh.toFloat() || p.getLong(k("sohAt"), 0L) < at || p.getString(k("sohSrc"), "") != src))
                p.edit().putFloat(k("soh"), soh.toFloat()).putFloat("soh_any", soh.toFloat())
                    .putLong(k("sohAt"), at).putLong("sohAt_any", at)
                    .putString(k("sohSrc"), src).putString("sohSrc_any", src).apply()
        }
        st.capacityKwh?.let { cap -> if (p != null && p.getFloat(k("srvCap"), 0f) != cap.toFloat()) p.edit().putFloat(k("srvCap"), cap.toFloat()).apply() }
        // The saved SoH is the one in use (a report's own value may have been ignored above).
        val shown = st.copy(batteryHealthPct = sohPct() ?: st.batteryHealthPct)
        val b = userKwh() ?: return shown
        return shown.copy(residualKwh = st.batteryPct?.let { it / 100.0 * b }, capacityKwh = b)
    }

    /** EV Remote: server trip energy is level change × the server's capacity; this factor makes it × the set battery size. */
    fun tripFactor(): Double? {
        val b = userKwh() ?: return null
        val cap = prefs?.getFloat(k("srvCap"), 0f)?.toDouble() ?: return null
        return if (cap > 1) b / cap else null
    }

    /**
     * Usable battery size: the user's setting, else what the car reports (or works out from energy left and %),
     * else a default for the car type (plug-in hybrids ~11.5 kWh, electric E-5008 73 kWh).
     */
    fun usableKwh(st: VehicleStatus): Double =
        userKwh()
            ?: st.capacityKwh?.takeIf { it > 1 }
            ?: st.residualKwh?.let { r -> st.batteryPct?.takeIf { it >= 10 }?.let { r / (it / 100.0) } }?.takeIf { it in 5.0..150.0 }
            ?: if (st.energyType == "Hybrid") 11.5 else 73.0

    private fun energyIn(st: VehicleStatus): Double? =
        st.residualKwh ?: st.batteryPct?.let { it / 100.0 * usableKwh(st) }

    /**
     * Call on every fresh car report. Fixes a missing/0 range and remembers charging samples.
     * Returns the status to use.
     */
    /** The last car reports, one line each, for the log (to see how the battery level moved). */
    fun history(): List<String> = prefs?.getString(k("hist"), "")?.split("\n")?.filter { it.isNotBlank() }.orEmpty()

    /**
     * The car reports its scheduled start time even when it is set to charge right away (the API has no on/off for it,
     * only "immediate" or "delayed" + a time), so LionFlow remembers what it last sent. Null when there is no
     * scheduled start, or it was set to "immediate".
     *
     * EV Remote: with "immediate" the time that was sent is kept too ("immediate,H:M,sentMillis"). When a later car
     * report (more than 15 s after the command) has another time, it was set in the car or in myOpel: "immediate" is
     * forgotten, so the start shows again and ChargeWake wakes the car for it.
     */
    fun scheduledStart(st: VehicleStatus): Pair<Int, Int>? {
        val h = st.nextDelayedHour ?: return null
        val m = st.nextDelayedMinute ?: 0
        val p = prefs ?: return h to m
        val mode = p.getString(k("startMode"), "").orEmpty()
        if (!mode.startsWith("immediate")) return h to m
        val parts = mode.split(",")
        val sent = parts.getOrNull(1)
        val sentAt = parts.getOrNull(2)?.toLongOrNull() ?: 0L
        val reportedAt = st.updatedAtMillis ?: 0L
        if (sent == null || sent == "$h:$m" || reportedAt <= sentAt + 15_000L) return null
        p.edit().putString(k("startMode"), "delayed").apply()
        try { log?.invoke("scheduled start changed outside EV Remote (now %02d:%02d): shown again".format(h, m)) } catch (_: Exception) { }
        return h to m
    }

    /** The time the car has for its delayed start, whether or not it is in use; null when the car reports none. */
    fun startTime(st: VehicleStatus): Pair<Int, Int>? = st.nextDelayedHour?.let { it to (st.nextDelayedMinute ?: 0) }

    /** EV Remote: when EV Remote last sent /VehCharge that the car accepted (0 if never). */
    fun lastStartSentAt(): Long = prefs?.getLong(k("startSentAt"), 0L) ?: 0L

    /**
     * EV Remote: is the car set to wait for its scheduled start? Plugged in, the car's own state decides: charging →
     * no (charging now); plugged in, stopped and with a start time → yes (waiting, also when the delay was set with
     * the car's own buttons). Unplugged or finished the car can't tell: what EV Remote last sent ([scheduledStart]).
     */
    fun delayedNow(st: VehicleStatus): Boolean = when {
        st.plugged && st.isCharging -> false
        waiting(st) -> true
        else -> scheduledStart(st) != null
    }

    private fun waiting(st: VehicleStatus) = st.plugged && st.chargingStatus == "Stopped" && st.nextDelayedHour != null

    /**
     * EV Remote: plugged in and waiting with a start time means the car is in delayed mode, so a remembered "immediate"
     * is forgotten (only for reports more than 5 min after it was sent, so the car had time to start charging).
     */
    private fun learnStartMode(st: VehicleStatus) {
        val p = prefs ?: return
        if (!waiting(st)) return
        val mode = p.getString(k("startMode"), "").orEmpty()
        if (!mode.startsWith("immediate")) return
        val sentAt = mode.split(",").getOrNull(2)?.toLongOrNull() ?: 0L
        if ((st.updatedAtMillis ?: 0L) <= sentAt + 5 * 60_000L) return
        p.edit().putString(k("startMode"), "delayed").apply()
        try { log?.invoke("car is plugged in and waiting for its scheduled start: delayed start on") } catch (_: Exception) { }
    }

    /** Called after /VehCharge was accepted: [hour]:[minute] is the time sent with it. */
    fun rememberStartMode(immediate: Boolean, hour: Int, minute: Int) {
        prefs?.edit()?.putString(k("startMode"), if (immediate) "immediate,$hour:$minute,${System.currentTimeMillis()}" else "delayed")
            ?.putLong(k("startSentAt"), System.currentTimeMillis())?.apply()
    }

    private fun remember(p: android.content.SharedPreferences, st: VehicleStatus) {
        val t = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.US).format(java.util.Date(st.updatedAtMillis ?: System.currentTimeMillis()))
        val line = "$t level=${st.batteryPct?.toInt()} residual=${st.residualKwh?.let { String.format(java.util.Locale.US, "%.1f", it) }} " +
            "usable=${st.capacityKwh?.let { String.format(java.util.Locale.US, "%.1f", it) }} soh=${st.batteryHealthPct?.let { String.format(java.util.Locale.US, "%.0f", it) }} " +
            "status=${st.chargingStatus} type=${st.chargeType} plugged=${st.plugged} preheat=${st.preconditioningOn}"
        val old = history()
        if (old.firstOrNull()?.substringAfter(" ")?.substringAfter(" ") == line.substringAfter(" ").substringAfter(" ")) return
        p.edit().putString(k("hist"), (listOf(line) + old).take(30).joinToString("\n")).apply()
    }

    /**
     * Some cars without a sunroof report one that is open ("RoofWindow": Open) all the time. A car that has one reports
     * it closed now and then, so the sunroof only counts once this car has been seen reporting it closed.
     * (Upstream c8703ec; it replaced fixLevel, so the battery % is always the car's own.)
     */
    private fun fixRoof(st: VehicleStatus): VehicleStatus {
        val p = prefs ?: return st
        val roof = st.raw.optJSONObject("doorsState")?.optJSONArray("opening")?.objects().orEmpty()
            .filter { it.optString("identifier").equals("RoofWindow", ignoreCase = true) }
        if (roof.any { it.optString("state") == "Closed" } && !p.getBoolean(k("roofSeen"), false)) p.edit().putBoolean(k("roofSeen"), true).apply()
        if (p.getBoolean(k("roofSeen"), false)) return st
        val doors = st.openDoors.filterNot { it.equals("RoofWindow", ignoreCase = true) }
        return if (doors.size == st.openDoors.size) st else st.copy(openDoors = doors)
    }


    /**
     * EV Remote: unplugged, the car reports pre-heating as "Disabled" even while it heats (battery drops ~3 kW).
     * It is taken as probably on when the car is unplugged, parked and awake (reported in the last 15 min) and that
     * report falls in a schedule's window (from ~25 min before the set time to 10 min after: unplugged the car starts
     * about 20 min before) or within 30 min after pre-heating was started from EV Remote (and not stopped since).
     */
    private fun fixPreheat(st: VehicleStatus): VehicleStatus {
        if (st.preconditioningOn || st.plugged || st.moving || st.energyType == "Fuel") return st
        val at = st.updatedAtMillis ?: return st
        if (System.currentTimeMillis() - at > 15 * 60_000L) return st
        val why = preheatReason(st, at) ?: return st
        val p = prefs
        if (p != null && p.getString(k("preheatGuess"), "") != "$why@${at / 3_600_000L}") {
            p.edit().putString(k("preheatGuess"), "$why@${at / 3_600_000L}").apply()
            try { log?.invoke("pre-heating reported off, but probably on: $why, unplugged, car awake") } catch (_: Exception) { }
        }
        return st.copy(preheatGuess = why)
    }

    private fun preheatReason(st: VehicleStatus, at: Long): String? {
        val p = prefs
        if (p != null) {
            val sent = p.getLong(k("precondOnAt"), 0L)
            if (sent > 0 && at >= sent - 60_000L && at <= sent + 30 * 60_000L) return "started from EV Remote"
            // Stopped from EV Remote: no guessing for the rest of that schedule window.
            val off = p.getLong(k("precondOffAt"), 0L)
            if (off > 0 && at <= off + 40 * 60_000L) return null
        }
        val days = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = at }
        val day = (cal.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7   // Mon = 0
        val min = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        for (pr in st.programs.filter { it.enabled }) for (shift in -1..1) {
            if (days[(day + shift + 7) % 7] !in pr.days) continue
            val t = shift * 1440 + pr.hour * 60 + pr.minute
            if (min in (t - 25)..(t + 10)) return "schedule %02d:%02d".format(pr.hour, pr.minute)
        }
        return null
    }

    /** EV Remote: pre-heating started (on) or stopped from EV Remote, for [fixPreheat]. */
    fun rememberPrecond(on: Boolean) {
        val now = System.currentTimeMillis()
        prefs?.edit()?.putLong(k("precondOnAt"), if (on) now else 0L)?.putLong(k("precondOffAt"), if (on) 0L else now)?.apply()
    }
    fun record(raw: VehicleStatus): VehicleStatus {
        // The level % is always the car's own (upstream 4eb9770/c8703ec dropped fixLevel); EV Remote's battery size
        // and SoH maths (applyBatterySize) is applied to it.
        val st = fixPreheat(applyPendingPrograms(fixRange(applyBatterySize(fixRoof(raw)))))
        try { ChargeLog.observe(st) } catch (_: Exception) { }
        try { CarTrips.observe(st) } catch (_: Exception) { }
        try { OutsideTemp.record(st) } catch (_: Exception) { }   // EV Remote: temperature per trip
        try { learnStartMode(st) } catch (_: Exception) { }   // EV Remote: the car's own delayed mode
        val p = prefs ?: return st
        remember(p, st)
        if (!st.isCharging) { p.edit().remove(k("s")).apply(); return st }
        val t = st.energyAtMillis ?: st.updatedAtMillis ?: return st
        val e = energyIn(st) ?: return st
        val arr = load()
        if (arr.isNotEmpty() && arr.last().first == t) return st
        val keep = (arr + (t to e)).filter { t - it.first <= 45 * 60_000L }.takeLast(12)
        p.edit().putString(k("s"), JSONArray(keep.map { JSONArray().put(it.first).put(it.second) }).toString()).apply()
        return st
    }

    // ---------------- pre-heating schedules just sent ----------------

    private fun key(p: Program) = listOf(p.days.sorted().joinToString(","), p.hour, p.minute, p.enabled).joinToString("|")

    /** Remember schedules sent to the car; Peugeot's status can show the old ones for a while. */
    fun setPendingPrograms(list: List<Program>) {
        settings?.pendingPrograms = org.json.JSONObject().put("time", System.currentTimeMillis())
            .put("programs", JSONArray(list.map { p ->
                org.json.JSONObject().put("slot", p.slot).put("days", JSONArray(p.days)).put("hour", p.hour).put("minute", p.minute).put("on", p.enabled)
            })).toString()
    }

    fun pendingPrograms(): List<Program>? = try {
        val raw = settings?.pendingPrograms.orEmpty()
        if (raw.isBlank()) null else {
            val j = org.json.JSONObject(raw)
            if (System.currentTimeMillis() - j.getLong("time") > 2 * 3_600_000L) { settings?.pendingPrograms = ""; null }
            else {
                val a = j.getJSONArray("programs")
                (0 until a.length()).map { i ->
                    val o = a.getJSONObject(i); val d = o.getJSONArray("days")
                    Program(o.getInt("slot"), (0 until d.length()).map { d.getString(it) }, o.getInt("hour"), o.getInt("minute"), o.getBoolean("on"))
                }
            }
        }
    } catch (_: Exception) { null }

    /** Until the car's status shows the new schedules, use the ones we sent (so they don't vanish or get overwritten). */
    private fun applyPendingPrograms(st: VehicleStatus): VehicleStatus {
        val pend = pendingPrograms() ?: return st
        val car = st.programs.map { key(it) }.toSet()
        val want = pend.map { key(it) }.toSet()
        if (car == want) { settings?.pendingPrograms = ""; return st }
        // The car's list is still the old one for a while. If it changes to something that is neither the old nor the new list,
        // someone else (another phone, MyPeugeot) edited the schedules: show the car's list instead of ours.
        val raw = org.json.JSONObject(settings?.pendingPrograms.orEmpty())
        val carKey = car.sorted().joinToString(";")
        val base = raw.optString("base", "")
        if (base.isEmpty()) { raw.put("base", carKey); settings?.pendingPrograms = raw.toString() }
        else if (base != carKey) { settings?.pendingPrograms = ""; return st }
        return st.copy(programs = pend)
    }

    /**
     * Peugeot's API sometimes reports 0 km range (often when plugged in or asleep) even with a full battery.
     * Then we use km-per-% learned from the last real reading, or a plain estimate (usable kWh at ~19 kWh/100 km).
     */
    private fun fixRange(st: VehicleStatus): VehicleStatus {
        if (st.energyType == "Fuel") return st
        val p = prefs
        val pct = st.batteryPct ?: return st
        val range = st.rangeKm
        if (range != null && range > 0 && pct >= 5) {
            val perPct = range / pct
            if (perPct in 1.0..10.0) p?.edit()?.putFloat(k("kmPerPct"), perPct.toFloat())?.apply()
            return st
        }
        if (range != null && range > 0) return st
        if (pct < 1) return st
        val learned = p?.getFloat(k("kmPerPct"), 0f)?.toDouble()?.takeIf { it > 0 }
        val perPct = learned ?: (usableKwh(st) / 19.0 * 100.0 / 100.0)
        return st.copy(rangeKm = Math.round(pct * perPct).toDouble(), rangeEstimated = true)
    }

    private fun load(): List<Pair<Long, Double>> = try {
        val a = JSONArray(prefs?.getString(k("s"), "[]") ?: "[]")
        (0 until a.length()).map { a.getJSONArray(it).let { x -> x.getLong(0) to x.getDouble(1) } }
    } catch (_: Exception) { emptyList() }

    fun reading(st: VehicleStatus?): Reading? {
        if (st == null || !st.isCharging) return null
        val max = if (st.chargingMode == "Quick") 250.0 else 23.0
        val samples = load()
        val newest = samples.lastOrNull()
        if (newest != null) {
            val base = samples.lastOrNull { newest.first - it.first in 4 * 60_000L..40 * 60_000L }
            if (base != null) {
                val kw = (newest.second - base.second) / ((newest.first - base.first) / 3_600_000.0)
                if (kw in 0.5..max) return Reading(kw, true)
            }
        }
        val rem = st.remainingMinutes?.takeIf { it > 0 } ?: return null
        val need = if (st.residualKwh != null && st.capacityKwh != null) st.capacityKwh - st.residualKwh
        else st.batteryPct?.let { usableKwh(st) * (100 - it) / 100.0 } ?: return null
        val kw = need / (rem / 60.0)
        return if (kw in 0.5..max) Reading(kw, false) else null
    }

    fun format(r: Reading): String =
        String.format(Locale.US, if (r.kw < 10) "%.1f kW" else "%.0f kW", r.kw) + if (r.measured) "" else " (est.)"

    fun label(st: VehicleStatus?): String? = reading(st)?.let { format(it) }
}
