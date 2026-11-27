package no.lionflow.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import no.lionflow.app.ui.l10n.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.setValue
import no.lionflow.app.ui.components.Toggle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import no.lionflow.app.api.WeatherApi
import no.lionflow.app.data.AppSettings
import no.lionflow.app.ui.AppViewModel
import no.lionflow.app.ui.LocalConfirm
import no.lionflow.app.ui.LocalPlatform
import no.lionflow.app.ui.ScreenColumn
import no.lionflow.app.ui.UiState
import no.lionflow.app.ui.components.Divider
import no.lionflow.app.ui.components.LfCard
import no.lionflow.app.ui.components.LfIcon
import no.lionflow.app.ui.components.LfIcons
import no.lionflow.app.ui.components.Pill
import no.lionflow.app.ui.components.PrimaryButton
import no.lionflow.app.ui.components.Ring
import no.lionflow.app.ui.components.ScreenHeader
import no.lionflow.app.ui.components.SecondaryButton
import no.lionflow.app.ui.fmtDistance
import no.lionflow.app.ui.fmtTemp
import no.lionflow.app.ui.theme.LocalLf
import no.lionflow.app.ui.theme.Type
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChargingScreen(vm: AppViewModel, ui: UiState, s: AppSettings, wide: Boolean) {
    val c = LocalLf.current
    val st = ui.status
    val confirm = LocalConfirm.current
    ScreenColumn(wide) {
        if (st?.energyType == "Fuel") {
            ScreenHeader(ui.vehicle?.let(vm::vehicleKind) ?: s.carName, "Charging")
            LfCard { Text("Charging controls are unavailable. Fuel level and range are on the Home screen.", style = Type.body, color = c.text2) }
            return@ScreenColumn
        }
        ScreenHeader(s.carName, "Charging")
        Box(Modifier.fillMaxWidth().height(230.dp), contentAlignment = Alignment.Center) {
            Ring(((st?.batteryPct ?: 0.0) / 100).toFloat(), c.meter, 210.dp)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(buildAnnotatedString {
                    append(st?.batteryPct?.let { "${it.toInt()}" } ?: "—")
                    withStyle(SpanStyle(color = c.muted, fontSize = 24.sp)) { append(" %") }
                }, style = Type.big.copy(fontSize = 54.sp), color = c.text)
                Text("≈ ${fmtDistance(st?.rangeKm, s)}", style = Type.small.copy(fontSize = 14.sp), color = c.muted)
            }
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.okBg).border(1.dp, c.okLine, RoundedCornerShape(18.dp)).padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            LfIcon(LfIcons.Bolt, c.accentInk, 20.dp)
            Text(chargingLine(st), style = Type.small.copy(fontSize = 14.sp), color = c.okText)
        }
        TimeToTarget(ui, s)
        LfCard {
            DetailRow("Charging power", no.lionflow.app.model.ChargePower.label(st) ?: if (st?.isCharging == true) "Calculating…" else "—")
            Divider()
            DetailRow("Range added per hour", st?.chargingRateKmh?.takeIf { it > 0 }?.let { "${fmtDistance(it, s)}/h" } ?: "—")
            Divider()
            DetailRow("Type", when (st?.chargingMode) { "Quick" -> "Fast (DC)"; "Slow" -> "AC"; else -> "—" })
            Divider()
            ScheduledStartSection(vm, ui, s, st)
            Divider()
            DetailRow("Battery health", healthText(st) ?: "—")
            EstimatedHealth(s, st)
        }
        // EV Remote: one big switch instead of "Charge now" / "Stop charging": Delayed while charging stops it.
        DelayedStartControl(vm, ui, s, st)
        Text(
            "Charging power is worked out by EV Remote (the car only reports range per hour): measured from the energy added between two car reports, or estimated from the time left until then. The car only knows \"charge now\" or \"wait for the scheduled start\": the switch above shows which one the car is set to and changes it.",
            style = Type.tiny, color = c.muted,
        )
        if (vm.remoteReady) ChargeLimitCard(vm, ui, s) else RemoteSetupHint(vm)
        ChargeLogSection(ui, s)
    }
}

/**
 * Battery health estimated from the phone's own charging log, shown under the car's official figure.
 * Only for cars that report the energy in the battery and whose size when new is known (set in Settings or reported).
 */
@Composable
fun EstimatedHealth(s: AppSettings, st: no.lionflow.app.model.VehicleStatus?, compact: Boolean = false) {
    val c = LocalLf.current
    if (st == null || st.residualKwh == null) return
    if (no.lionflow.app.model.ChargePower.userKwh() != null) return   // EV Remote: kWh are % × set battery size, nothing to estimate health from
    val sessions by no.lionflow.app.model.ChargeLog.sessions.collectAsState()
    val newKwh = s.batteryKwh.takeIf { it > 0 } ?: st.capacityKwh
    val est = no.lionflow.app.model.ChargeSession.healthEstimate(sessions, newKwh)
    val value = est?.let { "≈ ${it.first.toInt()} %" }
    if (compact) {
        if (value != null) Text("Estimated: $value", style = Type.tiny, color = c.muted)
        return
    }
    Divider()
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Estimated battery health based on charging sessions", style = Type.small.copy(fontSize = 14.sp), color = c.muted, modifier = Modifier.weight(1f))
            Text(value ?: "—", style = Type.label, color = c.text)
        }
        Text(
            if (est != null) "(not an official number) · from ${est.second} sessions"
            else "(not an official number) · needs 3 charging sessions of 15 % or more",
            style = Type.tiny, color = c.muted,
        )
    }
}

/**
 * EV Remote: the car's scheduled start time. The Opel API only has "immediate" or "delayed" + a time, so the time
 * is always editable (pencil, sent with the mode in use). Delayed or not is the big [DelayedStartControl].
 */
@Composable
private fun ScheduledStartSection(vm: AppViewModel, ui: UiState, s: AppSettings, st: no.lionflow.app.model.VehicleStatus?) {
    val c = LocalLf.current
    val confirm = LocalConfirm.current
    var editing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val time = st?.let { no.lionflow.app.model.ChargePower.startTime(it) }
    val canSend = vm.remoteReady && st != null && ui.commandInProgress == null
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Scheduled start", style = Type.small.copy(fontSize = 14.sp), color = c.muted, modifier = Modifier.weight(1f))
        androidx.compose.material3.Text(time?.let { hm24(it.first, it.second) } ?: no.lionflow.app.ui.l10n.L10n.tr("Not set"), style = Type.label, color = c.text)
        if (vm.remoteReady) Box(
            Modifier.padding(start = 8.dp).size(40.dp).clip(androidx.compose.foundation.shape.CircleShape)
                .clickable(enabled = canSend) { editing = true }
                .semantics { contentDescription = "Change scheduled start time" },
            contentAlignment = Alignment.Center,
        ) { LfIcon(LfIcons.Pencil, if (canSend) c.accentInk else c.muted, 18.dp) }
    }
    if (ui.commandInProgress == "Scheduled start time")
        androidx.compose.material3.Text(ui.commandProgress ?: no.lionflow.app.ui.l10n.L10n.tr("Sending…"), style = Type.tiny, color = c.accentInk)
    if (editing) ClockDialog("Scheduled start", time?.let { it.first * 60 + it.second } ?: 60, onClose = { editing = false }, is24 = true) { m ->
        editing = false
        val t = hm24(m / 60, m % 60)
        confirm(if (s.chargeWindowOn) "Change scheduled start to $t? The charging window starts then too." else "Change scheduled start to $t?") { vm.setStartTime(m) }
    }
}

internal fun hm24(h: Int, m: Int) = String.format(Locale.US, "%02d:%02d", h, m)

/**
 * EV Remote: a big two-way switch, "Charge now" | "Delayed 04:30", showing what the car does
 * (ChargePower.delayedNow): plugged in and waiting → Delayed (also when set with the car's own buttons),
 * charging → Charge now. Delayed while charging stops charging until the scheduled time; Charge now starts it.
 * With the charging window on it only shows the state ("Set by charging window"). Used on Charging and Home.
 */
@Composable
internal fun DelayedStartControl(vm: AppViewModel, ui: UiState, s: AppSettings, st: no.lionflow.app.model.VehicleStatus?, modifier: Modifier = Modifier, showState: Boolean = true) {
    if (!vm.remoteReady || st == null || !no.lionflow.app.ui.Caps.battery(st)) return
    val c = LocalLf.current
    val confirm = LocalConfirm.current
    val delayed = no.lionflow.app.model.ChargePower.delayedNow(st)
    val t = no.lionflow.app.model.ChargePower.startTime(st)?.let { hm24(it.first, it.second) } ?: "01:00"
    val locked = s.chargeWindowOn
    val busy = ui.commandInProgress?.let { it.startsWith("Delayed start") || it == "Stop charging" } == true
    val canSend = ui.commandInProgress == null && !locked
    val plugged = st.plugged
    val charging = st.isCharging
    fun pick(on: Boolean) {
        if (!canSend || on == delayed) return
        confirm(when {
            on && charging -> "Stop charging now and start again at $t?"
            on && plugged -> "Wait until $t to charge?"
            on -> "Delayed start on? When plugged in, the car waits until $t."
            plugged -> "Start charging now?"
            else -> "Delayed start off? When plugged in, the car charges right away."
        }) { vm.setDelayedStart(on) }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(30.dp)).background(c.raised)
                .border(1.dp, c.line2, RoundedCornerShape(30.dp)).padding(4.dp)
                .alpha(if (locked) 0.6f else 1f)
                .semantics { contentDescription = "Delayed start"; stateDescription = if (delayed) "Delayed until $t" else "Charge now" },
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            listOf(false to "Charge now", true to "Delayed · $t").forEach { (value, label) ->
                val sel = value == delayed
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(26.dp))
                        .background(if (sel) c.accent else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable(enabled = canSend && !sel) { pick(value) },
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.Text(
                        if (sel && busy) (ui.commandProgress ?: no.lionflow.app.ui.l10n.L10n.tr("Sending…")) else no.lionflow.app.ui.l10n.L10n.tr(label),
                        style = Type.label.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                        color = if (sel) c.onAccent else c.text2, maxLines = 1)
                }
            }
        }
        val state = when {
            locked -> "Set by charging window " + hm24(s.chargeWindowFrom / 60, s.chargeWindowFrom % 60) + "–" + hm24(s.chargeWindowTo / 60, s.chargeWindowTo % 60)
            charging -> "Charging now · Delayed stops it and starts again at $t"
            plugged && delayed -> "Plugged in · waiting until $t to charge"
            plugged && st.chargingStatus == "Finished" -> "Plugged in · charging finished"
            plugged -> "Plugged in · charges now"
            delayed -> "Not plugged in · when plugged in, waits until $t"
            else -> "Not plugged in · when plugged in, charges right away"
        }
        if (showState || locked) androidx.compose.material3.Text(no.lionflow.app.ui.l10n.L10n.tr(state), style = Type.tiny, color = c.muted,
            modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
@Composable
private fun DetailRow(label: String, value: String, action: String? = null, onAction: () -> Unit = {}) {
    val c = LocalLf.current
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Type.small.copy(fontSize = 14.sp), color = c.muted, modifier = Modifier.weight(1f))
        Text(value, style = Type.label, color = c.text)
        if (action != null) Text(action, style = Type.label, color = c.accentInk,
            modifier = Modifier.padding(start = 14.dp).clickable(onClick = onAction).padding(vertical = 6.dp))
    }
}

@Composable
fun ClimateScreen(vm: AppViewModel, ui: UiState, s: AppSettings, wide: Boolean) {
    val c = LocalLf.current
    val st = ui.status
    val confirm = LocalConfirm.current
    val platform = LocalPlatform.current
    ScreenColumn(wide) {
        ScreenHeader("Outside ${fmtTemp(st?.outsideTempC ?: ui.weather?.tempC, s)}", "Climate")
        LfCard(padding = PaddingValues(20.dp)) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LfIcon(LfIcons.Fan, if (st?.preheating == true) c.orangeInk else c.muted, 40.dp)
                Text(if (st?.preheating == true) "Pre-heating is on" else "Pre-heating is off", style = Type.stat, color = c.text)
                PreheatGuessNote(st)   // EV Remote
                if (vm.remoteReady) PrimaryButton(
                    text = when {
                        ui.commandInProgress?.contains("heating") == true -> "Sending…"
                        st?.preheating == true -> "Stop pre-heating"
                        else -> "Start pre-heating now"
                    },
                    modifier = Modifier.fillMaxWidth(),
                    color = c.orange,
                    enabled = st != null && ui.commandInProgress == null,
                ) {
                    val on = st?.preheating != true
                    confirm(if (on) "Start pre-heating" else "Stop pre-heating") { vm.preconditioning(on) }
                }
                Text("Heats or cools the cabin for a limited time (about 10–30 minutes, depending on the car). Uses grid power when plugged in.", style = Type.tiny, color = c.muted)
                PreheatLowBatteryNote(vm, st)   // EV Remote
            }
        }

        if (vm.remoteReady) PreheatSchedules(vm, ui) else RemoteSetupHint(vm)

        // Weather where you are
        LfCard {
            val w = ui.weather
            if (w == null) {
                Text("Weather here", style = Type.label, color = c.text)
                Text("Allow location to see the weather and forecast where you are.", style = Type.small, color = c.muted)
                SecondaryButton("Allow location") { platform.requestLocation() }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    WeatherIcon(WeatherApi.kind(w.symbol), 30)
                    Column {
                        Text("${fmtTemp(w.tempC, s)} · ${WeatherApi.describe(w.symbol)}", style = Type.stat, color = c.text)
                        Text("Your location · feels like ${fmtTemp(w.feelsLikeC, s)} · wind ${Math.round(w.windMs)} m/s", style = Type.tiny, color = c.muted)
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    val fmt = SimpleDateFormat(no.lionflow.app.ui.l10n.TimeFmt.pattern("HH:mm"), no.lionflow.app.ui.l10n.L10n.locale)
                    for (h in w.hours.take(6)) {
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(fmt.format(Date(h.timeMillis)), style = Type.tiny.copy(fontSize = 11.sp), color = c.muted)
                            WeatherIcon(WeatherApi.kind(h.symbol), 18)
                            Text(fmtTemp(h.tempC, s, unit = false), style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                        }
                    }
                }
                w.tomorrowMorningC?.let { t ->
                    Text(
                        "Tomorrow 07:00: ${fmtTemp(t, s)}" + if (t < 0) " — pre-heating recommended" else "",
                        style = Type.tiny, color = c.muted,
                    )
                }
                Text("Weather data: MET Norway", style = Type.tiny.copy(fontSize = 11.sp), color = c.muted,
                    modifier = Modifier.clickable { platform.openUrl("https://www.met.no/en/free-meteorological-data") })
            }
        }
    }
}

@Composable
fun WeatherIcon(kind: String, size: Int) {
    val c = LocalLf.current
    when (kind) {
        "sun" -> LfIcon(LfIcons.Sun, c.orangeInk, size.dp)
        "snow" -> LfIcon(LfIcons.Snow, c.blueInk, size.dp)
        "rain" -> LfIcon(LfIcons.Rain, c.blueInk, size.dp)
        else -> LfIcon(LfIcons.Cloud, c.muted, size.dp)
    }
}

private fun daysLabel(days: List<String>): String {
    val all = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    val set = days.toSet()
    return when {
        set.containsAll(all) -> "Every day"
        set == setOf("Mon", "Tue", "Wed", "Thu", "Fri") -> "Weekdays"
        set == setOf("Sat", "Sun") -> "Weekends"
        set.isEmpty() -> "Once"
        else -> all.filter { it in set }.joinToString(", ")
    }
}

/** Shown where remote buttons would be when remote control isn't set up (nothing if the car can't do it). */
@Composable
fun RemoteSetupHint(vm: AppViewModel) {
    if (!vm.remotePossible) return
    val c = LocalLf.current
    Text("Remote control isn't set up — tap to enable", style = Type.small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = c.accentInk,
        modifier = Modifier.clickable { vm.openRemoteSetup() }.padding(vertical = 4.dp))
    NeedsPackHint(electric = true)
}


/** When the battery reaches the charge target (the limit if one is set, otherwise 80 %), with a button to set a phone alarm for it. */
@Composable
private fun TimeToTarget(ui: UiState, s: AppSettings) {
    val c = LocalLf.current
    val platform = LocalPlatform.current
    val st = ui.status ?: return
    if (!st.isCharging) return
    val soc = st.batteryPct ?: return
    val rem = st.remainingMinutes ?: return
    val target = if (s.chargeLimitOn) s.chargeLimitPct else 80
    if (soc >= target) {
        LfCard { Text("Already past $target %", style = Type.body, color = c.text2) }
        return
    }
    // AC charging is steady, so the time to the target is the car's time to 100 % scaled by how much of the climb is left.
    val minutes = rem * (target - soc) / (100 - soc).coerceAtLeast(1.0)
    val now = System.currentTimeMillis()
    val base = st.updatedAtMillis?.takeIf { now - it < 30 * 60_000L } ?: now
    val at = maxOf(base + (minutes * 60_000).toLong(), now + 60_000L)
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = at }
    val h = cal.get(java.util.Calendar.HOUR_OF_DAY); val m = cal.get(java.util.Calendar.MINUTE)
    val clock = no.lionflow.app.ui.l10n.TimeFmt.hm(h, m)
    val left = ((at - now) / 60_000L).toInt()
    LfCard {
        Text("Reaches $target % at $clock", style = Type.label.copy(fontSize = 18.sp), color = c.text)
        Text("In " + (if (left >= 60) "${left / 60} h ${left % 60} min" else "$left min"), style = Type.small, color = c.muted)
        Spacer(Modifier.height(10.dp))
        SecondaryButton("Set alarm for $clock", Modifier.fillMaxWidth()) { platform.setAlarm(h, m, "EV Remote: car at $target %") }
        Text("Worked out from the car's time to 100 %. Handy if the automatic stop doesn't work for you: stop charging yourself when the alarm rings.", style = Type.tiny, color = c.muted, modifier = Modifier.padding(top = 8.dp))
    }
}

/** EV Remote: next to Pre-heat, when the car is unplugged and under 55 % (it may refuse to pre-heat below ~50 %). */
@Composable
internal fun PreheatLowBatteryNote(vm: AppViewModel, st: no.lionflow.app.model.VehicleStatus?, modifier: Modifier = Modifier) {
    if (!vm.preheatLowBattery(st)) return
    val c = LocalLf.current
    Text("Not plugged in: the car may refuse to pre-heat below about 50 % battery. Plug in or charge first.",
        style = Type.tiny, color = c.orangeInk, modifier = modifier)
}

/** EV Remote: why pre-heating is shown as on although the car reports it off (unplugged). */
@Composable
fun PreheatGuessNote(st: no.lionflow.app.model.VehicleStatus?, modifier: Modifier = Modifier) {
    val why = st?.preheatGuess ?: return
    val c = LocalLf.current
    androidx.compose.material3.Text(
        no.lionflow.app.ui.l10n.L10n.tr("Probably on") + " (" + why + "): " +
            no.lionflow.app.ui.l10n.L10n.tr("the car reports pre-heating as off while it heats unplugged."),
        style = Type.tiny, color = c.orangeInk, modifier = modifier)
}
