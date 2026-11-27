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

    /** EV Remote: usable capacity now = new battery × SoH (100 % until the car has sent one). */
    fun userKwh(): Double? = newKwh()?.let { it * (sohPct() ?: 100.0) / 100.0 }

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
        VehicleStatus.sohOf(st.raw)?.let { soh ->
            val at = st.updatedAtMillis ?: System.currentTimeMillis()
            if (p != null && (p.getFloat(k("soh"), 0f) != soh.toFloat() || p.getLong(k("sohAt"), 0L) < at))
                p.edit().putFloat(k("soh"), soh.toFloat()).putFloat("soh_any", soh.toFloat())
                    .putLong(k("sohAt"), at).putLong("sohAt_any", at).apply()
        }
        st.capacityKwh?.let { cap -> if (p != null && p.getFloat(k("srvCap"), 0f) != cap.toFloat()) p.edit().putFloat(k("srvCap"), cap.toFloat()).apply() }
        val shown = st.copy(batteryHealthPct = st.batteryHealthPct ?: sohPct())
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
     * The car reports its scheduled start time even when it is set to charge right away (the API has no on/off for it),
     * so LionFlow remembers what it last sent. Null when there is no scheduled start, or LionFlow turned it off.
     */
    fun scheduledStart(st: VehicleStatus): Pair<Int, Int>? {
        val h = st.nextDelayedHour ?: return null
        if (prefs?.getString(k("startMode"), "") == "immediate") return null
        return h to (st.nextDelayedMinute ?: 0)
    }

    fun rememberStartMode(immediate: Boolean) {
        prefs?.edit()?.putString(k("startMode"), if (immediate) "immediate" else "delayed")?.apply()
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

    fun record(raw: VehicleStatus): VehicleStatus {
        // The level % is always the car's own (upstream 4eb9770/c8703ec dropped fixLevel); EV Remote's battery size
        // and SoH maths (applyBatterySize) is applied to it.
        val st = applyPendingPrograms(fixRange(applyBatterySize(fixRoof(raw))))
        try { ChargeLog.observe(st) } catch (_: Exception) { }
        try { CarTrips.observe(st) } catch (_: Exception) { }
        try { OutsideTemp.record(st) } catch (_: Exception) { }   // EV Remote: temperature per trip
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
