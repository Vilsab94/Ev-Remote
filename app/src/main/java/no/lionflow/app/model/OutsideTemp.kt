package no.lionflow.app.model

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * EV Remote: outside temperatures the car reported (status environment.air.temp), with their time, kept on the
 * phone so each trip can show the temperature nearest to its start (cold weather raises the battery use,
 * partly for heating the battery, which the car's own trip computer doesn't count).
 */
object OutsideTemp {
    private var prefs: SharedPreferences? = null
    private const val MAX = 3000                      // a few months of reports
    private const val NEAR = 3 * 3_600_000L           // a reading within 3 h of a trip's start counts

    @Volatile private var cache: List<Pair<Long, Double>>? = null

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("outside_temp", Context.MODE_PRIVATE)
    }

    private fun load(): List<Pair<Long, Double>> = cache ?: try {
        val a = JSONArray(prefs?.getString("t", "[]") ?: "[]")
        (0 until a.length()).map { a.getJSONArray(it).let { x -> x.getLong(0) to x.getDouble(1) } }
    } catch (_: Exception) { emptyList() }.also { cache = it }

    /** Call with every fresh car report. */
    @Synchronized
    fun record(st: VehicleStatus) {
        val temp = st.outsideTempC?.takeIf { !it.isNaN() && it in -60.0..60.0 } ?: return
        val t = st.updatedAtMillis ?: return
        val list = load()
        if (list.any { it.first == t }) return
        val keep = (list + (t to temp)).sortedBy { it.first }.takeLast(MAX)
        cache = keep
        prefs?.edit()?.putString("t", JSONArray(keep.map { JSONArray().put(it.first).put(it.second) }).toString())?.apply()
    }

    /** The reading nearest to [millis] (within 3 h), or null. */
    fun at(millis: Long?): Double? {
        if (millis == null) return null
        return load().minByOrNull { kotlin.math.abs(it.first - millis) }?.takeIf { kotlin.math.abs(it.first - millis) <= NEAR }?.second
    }

    /** Average of the trips' temperatures (each trip's nearest reading), or null when none is known. */
    fun average(trips: List<Trip>): Double? = trips.mapNotNull { at(it.startMillis) }.takeIf { it.isNotEmpty() }?.average()
}
