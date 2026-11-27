package no.lionflow.app.model

import android.content.Context
import no.lionflow.app.api.obj
import no.lionflow.app.api.objects
import no.lionflow.app.api.str
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * EV Remote (PLAN.md item 3): a trip's route points from the server (the trip's _links.wayPoints.href). The format
 * isn't documented, so the points are looked for in the usual places (HAL _embedded list, wayPoints, features,
 * points, a LineString) and each point's position read as GeoJSON or lat/lon. Points are kept on the phone as
 * "lat,lon,time" lines, like the routes the phone records itself.
 */
object WayPoints {
    fun dir(ctx: Context) = File(ctx.filesDir, "waypoints").apply { mkdirs() }
    private fun file(ctx: Context, tripId: String) = File(dir(ctx), tripId.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".csv")
    fun debugFile(ctx: Context) = File(dir(ctx), "last_answer.txt")

    /** The point objects in a wayPoints answer, in order. */
    fun rawPoints(answer: Any?): List<JSONObject> {
        if (answer is JSONArray) return answer.objects()
        val o = answer as? JSONObject ?: return emptyList()
        o.optJSONObject("_embedded")?.let { e -> e.keys().asSequence().mapNotNull { e.optJSONArray(it) }.firstOrNull()?.let { return it.objects() } }
        for (k in listOf("wayPoints", "waypoints", "features", "points", "items", "positions")) o.optJSONArray(k)?.let { return it.objects() }
        // One LineString with all coordinates.
        val line = o.obj("geometry")?.takeIf { it.optString("type") == "LineString" }?.optJSONArray("coordinates")
        if (line != null) return (0 until line.length()).mapNotNull { line.optJSONArray(it) }
            .map { JSONObject().put("coordinates", it) }
        return emptyList()
    }

    private fun timeOf(p: JSONObject): Long {
        val props = p.optJSONObject("properties")
        for (k in listOf("createdAt", "timestamp", "time", "date", "updatedAt", "recordedAt")) {
            (p.str(k) ?: props?.str(k))?.let { s -> VehicleStatus.parseIso(s)?.let { return it }; s.toLongOrNull()?.let { return it } }
        }
        return 0L
    }

    /** (lat, lon, time) for each point with a usable position; time 0 when the server gives none. */
    fun parse(answer: Any?): List<Triple<Double, Double, Long>> =
        rawPoints(answer).mapNotNull { p -> Trip.latLonOf(p)?.let { (la, lo) -> Triple(la, lo, timeOf(p)) } }

    fun saved(ctx: Context, tripId: String): List<Triple<Double, Double, Long>>? {
        val f = file(ctx, tripId)
        if (!f.exists()) return null
        return try {
            f.readLines().mapNotNull { l -> l.split(",").takeIf { it.size >= 3 }?.let { Triple(it[0].toDouble(), it[1].toDouble(), it[2].toLong()) } }
        } catch (_: Exception) { null }
    }

    fun save(ctx: Context, tripId: String, pts: List<Triple<Double, Double, Long>>) {
        try { file(ctx, tripId).writeText(pts.joinToString("") { "${it.first},${it.second},${it.third}\n" }) } catch (_: Exception) { }
    }

    /** For "All car data": the HTTP result and the first and last point exactly as the server sent them. */
    fun saveDebug(ctx: Context, tripId: String, result: String, answer: Any?) {
        val pts = rawPoints(answer)
        val text = "trip $tripId: $result, ${pts.size} points\nfirst point: " +
            (pts.firstOrNull()?.toString(2) ?: "—") + "\nlast point: " + (pts.lastOrNull()?.toString(2) ?: "—") +
            (if (pts.isEmpty() && answer != null) "\nanswer: " + answer.toString().take(1500) else "")
        try { debugFile(ctx).writeText(text) } catch (_: Exception) { }
    }

    fun debug(ctx: Context): String? = try { debugFile(ctx).takeIf { it.exists() }?.readText() } catch (_: Exception) { null }

    /**
     * A failed answer (HTTP 5xx, other error, or 0 points) is not final: the trip is asked again when trips are loaded
     * again, at most every [RETRY_MS] and [MAX_TRIES] times per trip. Kept as "tries,lastTryMillis" per trip.
     */
    const val MAX_TRIES = 5
    const val RETRY_MS = 15 * 60_000L
    private fun tries(ctx: Context) = ctx.getSharedPreferences("ev_remote", Context.MODE_PRIVATE)
    private fun triesKey(tripId: String) = "wpTry_$tripId"

    fun mayTry(ctx: Context, tripId: String, now: Long = System.currentTimeMillis()): Boolean {
        val v = tries(ctx).getString(triesKey(tripId), "")!!.split(",").mapNotNull { it.toLongOrNull() }
        val n = v.getOrNull(0) ?: 0L; val at = v.getOrNull(1) ?: 0L
        return n < MAX_TRIES && now - at >= RETRY_MS
    }

    /** Returns which try this is (1 = first). */
    fun noteTry(ctx: Context, tripId: String, now: Long = System.currentTimeMillis()): Int {
        val p = tries(ctx)
        val n = (p.getString(triesKey(tripId), "")!!.substringBefore(",").toIntOrNull() ?: 0) + 1
        p.edit().putString(triesKey(tripId), "$n,$now").apply()
        return n
    }

    fun clearTries(ctx: Context, tripId: String) { tries(ctx).edit().remove(triesKey(tripId)).apply() }
}
