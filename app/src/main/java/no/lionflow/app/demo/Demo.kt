package no.lionflow.app.demo

import android.content.Context
import no.lionflow.app.api.Vehicle
import no.lionflow.app.model.ChargePlace
import no.lionflow.app.model.ChargeSession
import no.lionflow.app.model.PlacesConfig
import no.lionflow.app.model.Program
import no.lionflow.app.model.Trip
import no.lionflow.app.model.VehicleStatus
import org.json.JSONObject
import kotlin.random.Random

/**
 * Demo mode: a sample car with made-up data, for trying the app (and for Google Play's reviewers)
 * without a MyPeugeot account. Nothing is sent anywhere; commands only change the sample car.
 */
object Demo {
    const val VIN = "VR3DEMOLIONFLOW00"
    val vehicle = Vehicle(id = "demo", vin = VIN, motorization = "Electric", brand = "Peugeot", pictureUrl = null, maintenanceHref = null)

    // A neutral spot (Paris) for the sample car's home.
    private const val HOME_LAT = 48.8566
    private const val HOME_LON = 2.3522

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("demo", Context.MODE_PRIVATE)
    fun isOn(ctx: Context) = prefs(ctx).getBoolean("on", false)
    fun setOn(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean("on", on).apply() }

    // The sample car's state; commands change it.
    private var battery = 64.0
    private var locked = true
    private var precond = false
    private var charging = true
    private var plugged = true
    private var chargeType = "Partial"
    private var programs = listOf(
        Program(1, listOf("Mon", "Tue", "Wed", "Thu", "Fri"), 7, 15, true),
        Program(2, listOf("Sat"), 9, 0, false),
        Program(3, emptyList(), 0, 0, false),
        Program(4, emptyList(), 0, 0, false),
    )

    fun reset() { battery = 64.0; locked = true; precond = false; charging = true; plugged = true; chargeType = "Partial" }

    fun status(): VehicleStatus {
        val now = System.currentTimeMillis()
        val raw = JSONObject().put("preconditioning", JSONObject()).put("demo", true)
        return VehicleStatus(
            raw = raw, batteryPct = battery, rangeKm = battery * 4.3, plugged = plugged,
            chargingStatus = if (charging) "InProgress" else if (plugged) "Stopped" else "Disconnected",
            chargingMode = if (charging) "Slow" else "No", chargingRateKmh = if (charging) 48.0 else null,
            remainingMinutes = if (charging) ((80 - battery).coerceAtLeast(0.0) * 9).toInt() else null,
            nextDelayedHour = 1, nextDelayedMinute = 0, batteryHealthPct = 98.0, odometerKm = 13_412.0,
            locked = locked, openDoors = emptyList(), outsideTempC = 6.0, preconditioningOn = precond,
            programs = programs, lat = HOME_LAT, lon = HOME_LON, ignition = "Stop", moving = false,
            updatedAtMillis = now - 2 * 60_000, kmToService = 11_588.0, daysToService = 270.0,
            residualKwh = battery / 100 * 73, capacityKwh = 73.0, energyAtMillis = now, chargeType = chargeType,
            heading = 118.0, privacyState = "None",
        )
    }

    /** Applies a simulated command to the sample car. */
    fun apply(label: String, programsNew: List<Program>? = null) {
        when (label) {
            "Lock" -> locked = true
            "Unlock" -> locked = false
            "Pre-heating" -> precond = true
            "Stop pre-heating" -> precond = false
            "Charge now" -> { plugged = true; charging = true }
            "Stop charging" -> charging = false
            "Daily charge limit" -> chargeType = "Partial"
            "Full charge for a trip" -> chargeType = "Full"
        }
        programsNew?.let { programs = it }
    }

    fun trips(): List<Trip> {
        val r = Random(42)
        val day = 86_400_000L
        val start = System.currentTimeMillis() - 30 * day
        val out = mutableListOf<Trip>()
        for (d in 0 until 30) {
            val n = r.nextInt(1, 4)
            for (i in 0 until n) {
                val t = start + d * day + (7 + i * 5 + r.nextInt(0, 3)) * 3_600_000L + r.nextInt(0, 50) * 60_000L
                val km = if (r.nextInt(10) == 0) r.nextDouble(60.0, 140.0) else r.nextDouble(3.0, 28.0)
                val speed = if (km > 50) r.nextDouble(70.0, 95.0) else r.nextDouble(28.0, 55.0)
                val sec = km / speed * 3600
                val per100 = r.nextDouble(15.5, 21.0)
                out += Trip("demo-$d-$i", t, t + (sec * 1000).toLong(), km, sec, km * per100 / 100, per100, speed)
            }
        }
        return out.sortedByDescending { it.startMillis }
    }

    fun places() = PlacesConfig(
        home = ChargePlace("home", "Home", "Sample street 1, Paris", HOME_LAT, HOME_LON, 0.25),
        known = listOf(ChargePlace("k1", "Work", "Office car park", HOME_LAT + 0.03, HOME_LON + 0.04, 0.0)),
        publicAcPrice = 0.45, publicDcPrice = 0.69, currency = "€",
    )

    fun sessions(): List<ChargeSession> {
        val r = Random(7)
        val day = 86_400_000L
        val now = System.currentTimeMillis()
        return (1..14).map { i ->
            val where = when (i % 5) { 0 -> 2; 3 -> 1; else -> 0 } // 0 home, 1 work, 2 fast charger
            val s = now - i * 2 * day - r.nextInt(0, 6) * 3_600_000L
            val from = r.nextDouble(18.0, 50.0)
            val to = if (where == 2) r.nextDouble(70.0, 82.0) else 80.0
            val kwh = (to - from) / 100 * 73
            val kw = when (where) { 2 -> r.nextDouble(70.0, 110.0); else -> r.nextDouble(6.5, 10.5) }
            val (lat, lon, addr) = when (where) {
                0 -> Triple(HOME_LAT, HOME_LON, "Sample street 1, Paris")
                1 -> Triple(HOME_LAT + 0.03, HOME_LON + 0.04, "Office car park")
                else -> Triple(HOME_LAT + 0.4, HOME_LON - 0.3, "Motorway services A10")
            }
            ChargeSession(id = s, start = s, end = s + (kwh / kw * 3_600_000).toLong(), startPct = from, endPct = to, kwh = kwh,
                peakKw = kw * 1.15, mode = if (where == 2) "Quick" else "Slow", lat = lat, lon = lon, address = addr,
                source = if (where == 2) "Fast charger" else null)
        }
    }
}
