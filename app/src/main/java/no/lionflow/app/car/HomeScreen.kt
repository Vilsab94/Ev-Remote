package no.lionflow.app.car

import android.graphics.Canvas
import android.graphics.Paint
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import no.lionflow.app.R
import no.lionflow.app.car.ui.Hit
import no.lionflow.app.car.ui.HomeModel
import no.lionflow.app.car.ui.HomePaint
import no.lionflow.app.car.ui.Kit
import no.lionflow.app.car.ui.Tone
import no.lionflow.app.model.ChargePower
import no.lionflow.app.model.Garage
import no.lionflow.app.model.VehicleStatus
import no.lionflow.app.plan.SavedRoutes
import no.lionflow.app.ui.fmtDistance
import no.lionflow.app.ui.fmtTemp
import no.lionflow.app.ui.l10n.tr
import kotlin.math.roundToInt

/** "1 h 20 min" / "35 min", in the app's language. */
internal fun carDuration(minutes: Double): String {
    val t = minutes.roundToInt().coerceAtLeast(0)
    return if (t >= 60) tr("${t / 60} h ${t % 60} min") else "$t min"
}

/** What goes under a screen's title: what is happening right now, else how fresh the car's numbers are. */
internal fun carStatusLine(): Pair<String, Int> {
    CarHub.message.value?.let { return tr(it) to Tone.TEXT }
    if (CarHub.loading.value && CarHub.status.value != null) return tr("Updating…") to Tone.MUTED
    return tr(CarHub.updatedLine()) to Tone.MUTED
}

/** "NRK" in a ring, for the button that opens NRK TV. */
internal fun nrkBadge(): CarIcon {
    val n = 96
    val b = android.graphics.Bitmap.createBitmap(n, n, android.graphics.Bitmap.Config.ARGB_8888)
    val c = Canvas(b)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = 0xFFFFFFFF.toInt(); p.style = Paint.Style.STROKE; p.strokeWidth = n * 0.07f
    c.drawCircle(n / 2f, n / 2f, n / 2f - p.strokeWidth, p)
    p.style = Paint.Style.FILL; p.textAlign = Paint.Align.CENTER
    p.typeface = android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD); p.textSize = n * 0.30f
    c.drawText("NRK", n / 2f, n / 2f + p.textSize * 0.36f, p)
    return CarIcon.Builder(IconCompat.createWithBitmap(b)).build()
}

/**
 * The start screen in the car: the car's name, a battery gauge with what the car is doing, and big tiles for the
 * rest. Dark or light (the sun / moon button switches; without a choice it follows the car).
 */
internal class HomeScreen(ctx: CarContext, ui: CarUi) : DrawnScreen(ctx, ui) {
    init {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { combine(CarHub.status, CarHub.message, CarHub.loading, CarHub.trips) { _, _, _, _ -> Unit }.collect { redraw() } }
                launch { SavedRoutes.changes.collect { redraw() } }
                launch { while (true) { delay(10_000L); CarHub.refresh(carContext, force = true); CarHub.askCar(carContext) } }   // fresh numbers while it shows
                launch { CarHub.live.collect { redraw() } }   // battery and range straight from the car
                launch { while (true) { delay(15_000L); redraw() } }                                  // keeps "reported … ago" right
            }
        }
    }

    /** Whether there is more than one car to switch between (looked up when the screen comes on show). */
    private var several = false

    override fun shown() {
        several = Garage.hasSeveral(CarHub.app(carContext))
        ui.guard.askPermissions()
        CarHub.refresh(carContext)
        CarHub.askCar(carContext)
        CarHub.loadTrips(carContext)
        // Whether the NRK button belongs here is looked up in the background the first time.
        Where.warm(carContext) { invalidate() }
    }

    private fun openVideo(site: VideoSite) {
        ui.guard.update()
        if (ui.guard.parked.value) screenManager.push(YouTubeScreen(carContext, ui, site))
        else CarToast.makeText(carContext, tr(ui.guard.reason), CarToast.LENGTH_LONG).show()
    }

    override val themeButton: Boolean get() = false

    override fun buttons(): List<Btn> {
        val pal = CarTheme.refresh(carContext)
        val out = ArrayList<Btn>()
        // Sun in the dark look (switch to light), moon in the light look.
        out += Btn(if (pal.dark) R.drawable.ic_car_sun else R.drawable.ic_car_moon) { CarTheme.toggle(carContext); invalidate(); redraw() }
        out += Btn(R.drawable.ic_car_refresh) { CarHub.refresh(carContext, force = true); CarHub.loadTrips(carContext, force = true) }
        out += Btn(R.drawable.ic_car_play, tint = CarColor.RED) { openVideo(VideoSite.YouTube) }
        if (Where.inNorway(carContext)) out += Btn(custom = nrkBadge()) { openVideo(VideoSite.NrkTv) }
        return out
    }

    override fun draw(c: Canvas, k: Kit, pressed: String?, hits: MutableList<Hit>) = HomePaint.draw(c, k, model(), pressed, hits)

    override fun tapped(key: String) {
        when (key) {
            "plan" -> screenManager.push(TripSearchScreen(carContext, ui))
            "chargers" -> screenManager.push(ChargerListScreen(carContext, ui))
            "saved" -> screenManager.push(SavedListScreen(carContext, ui))
            "trips" -> screenManager.push(TripListScreen(carContext, ui))
            "info", HomePaint.GAUGE -> screenManager.push(CarInfoScreen(carContext, ui))
            "switch" -> screenManager.push(CarListScreen(carContext, ui))
        }
    }

    private fun model(): HomeModel {
        val s = CarHub.app(carContext).settings.value
        val st = CarHub.status.value
        val (status, tone) = carStatusLine()
        val fuelOnly = st?.energyType == "Fuel"

        val tiles = ArrayList<HomeModel.Tile>()
        // Planning is about the battery: not for cars that only burn fuel.
        if (!fuelOnly) tiles += HomeModel.Tile("plan", tr("Plan a trip"), tr("Battery % on arrival"), R.drawable.ic_car_search)
        tiles += HomeModel.Tile("chargers", tr(if (fuelOnly) "Fuel stations" else if (st?.energyType == "Hybrid") "Chargers & fuel" else "Chargers"), tr("Nearest first"),
            if (fuelOnly) R.drawable.ic_car_fuel else R.drawable.ic_car_charger)
        val saved = if (SavedRoutes.fromPlay(carContext)) SavedRoutes.all(carContext) else emptyList()
        if (saved.isNotEmpty()) tiles += HomeModel.Tile("saved", tr("Saved routes"), if (saved.size == 1) saved[0].name else tr("${saved.size} routes"), R.drawable.ic_car_route)
        val trips = CarHub.trips.value
        val weekKm = trips?.filter { (it.startMillis ?: 0L) >= System.currentTimeMillis() - 7 * 86_400_000L }?.sumOf { it.distanceKm }
        tiles += HomeModel.Tile("trips", tr("Previous trips"), if (weekKm == null) tr("Loading…") else tr("7 days: ${fmtDistance(weekKm, s)}"), R.drawable.ic_car_history)
        tiles += HomeModel.Tile("info", tr("Car Info"), st?.odometerKm?.let { fmtDistance(it, s) } ?: tr("Details from the car"), R.drawable.ic_car_gauge)
        if (several) tiles += HomeModel.Tile("switch", tr("Switch car"), s.carName, R.drawable.ic_car_switch)

        return HomeModel(s.carName, status, tone, st?.let { gauge(it, s) }, tr("Getting the car's status…"), tiles)
    }

    private fun gauge(st: VehicleStatus, s: no.lionflow.app.data.AppSettings): HomeModel.Gauge {
        val fuelOnly = st.energyType == "Fuel"
        // The car's own reading (live, while Android Auto runs) before the one from the brand's server.
        val live = CarHub.live.value
        val pct = if (fuelOnly) st.fuelPct ?: st.batteryPct else live.pct ?: st.batteryPct
        val range = if (fuelOnly) st.fuelRangeKm ?: st.rangeKm else live.rangeKm ?: st.rangeKm
        val lines = ArrayList<HomeModel.Line>()
        val doors = HomeModel.Line(R.drawable.ic_car_lock, CarHub.doorsLine(st), if (st.openDoors.isNotEmpty() || st.locked == false) Tone.WARN else Tone.TEXT)
        if (fuelOnly) lines += doors
        else when {
            st.isCharging -> {
                lines += HomeModel.Line(R.drawable.ic_car_bolt, tr("Charging") + (ChargePower.label(st)?.let { " · $it" } ?: ""), Tone.GOOD)
                st.remainingMinutes?.takeIf { it > 0 }?.let { lines += HomeModel.Line(R.drawable.ic_car_history, tr("Full in") + " " + carDuration(it.toDouble()), Tone.GOOD) }
                lines += doors
            }
            else -> {
                lines += doors
                lines += HomeModel.Line(R.drawable.ic_car_bolt, tr(when { st.chargingStatus == "Finished" -> "Charging complete"; st.plugged -> "Plugged in"; else -> "Not plugged in" }),
                    if (st.plugged || st.chargingStatus == "Finished") Tone.GOOD else Tone.TEXT)
            }
        }
        // A plug-in hybrid: the tank in one short line (its range is in Car Info).
        if (!fuelOnly && st.fuelPct != null) lines += HomeModel.Line(R.drawable.ic_car_fuel, tr("Fuel") + " ${st.fuelPct.roundToInt()} %", Tone.TEXT)
        st.outsideTempC?.let { lines += HomeModel.Line(R.drawable.ic_car_climate, fmtTemp(it, s) + " " + tr("outside"), Tone.TEXT) }
        if (st.preconditioningOn) lines += HomeModel.Line(R.drawable.ic_car_climate, tr("Pre-heating"), Tone.WARN)
        return HomeModel.Gauge(pct, range?.let { fmtDistance(it, s) } ?: "—", lines)
    }
}
