# EV Remote – planned changes (Opel Mokka-e 2022)

Findings from testing LionFlow with this car (Nov 2026):

1. ✅ **Done (2026-11-26)** – usable = "Battery size" (new battery, default 46 kWh) × SoH (health.capacity,
   last one saved, 100 % until received); energy = level % × usable for trips, charging kW, time estimates and
   the charge log (see UPSTREAM.md). The optional calibration against the car's trip display is not done.
   **Battery capacity is wrong on the server.** `capacity` = 32960 Wh, `residual` implies ~27 kWh full,
   real usable is ~43 kWh (46 kWh × SoH 93 %). The battery % (level) is correct.
   Trip kWh (`energyConsumptions[].consumption`) is just Δ% × ~33 kWh.
   → Compute energy from level % × user battery size (default 43 kWh); optionally calibrate a factor
     against the car's own trip display. Apply to trip kWh/100 km, charging kW, charge log kWh.
     The "Battery size" setting must override server residual/capacity everywhere.
   Sample: 28 km trip, car 17.1 kWh/100, LionFlow 10.0 kWh/100 (−9 %, 2.8 kWh).
   2026-11-27: EV Remote logged soh=null while LionFlow showed 93 % – LionFlow falls back to
   `health.resistance` when `capacity` is missing. SoH: capacity, else resistance – the Mokka-e sends only
   resistance = 93 and an OBD reader confirmed 93 %, so usable = 46 × 0,93 ≈ 42,8 kWh. Shown as
   "93 % · resistance · received <date>". Every status logs "health: capacity=…, resistance=…".
   The car sometimes sends resistance = 100 (not worked out): a higher SoH is taken only after 60 days
   ("health ignored: 100 (kept 93)"), so usable doesn't jump between 42,8 and 46 kWh.
   Trips: kWh/100 km is labelled "from battery" (cold: battery heating counts, the car's trip computer leaves it out),
   with the outside temperature per trip; under 3 % battery drop shown as "≈" in grey.
2. ✅ **Done (2026-11-27, not yet tested on the car)** – **Charge limit (80 %) fails on 11 kW AC**: the car
   sends no status while charging (02:00 start, next report 05:00 at 100 %; also plugged 20:17 at 29 %, nothing
   until 05:57 at 78 %). Background check only reads the server.
   → Done as `work/ChargeWake.kt`: exact alarm ~3 min after nextDelayedTime; while charging wake + read every
     ~15 min (~5 min within 8 % of the limit) and run the charge limit; stop when finished/stopped/unplugged;
     never wake an unplugged car; 3 unanswered wakes → stop + notification; battery optimization asked once.
3. ✅ **Done (2026-11-26/27, not yet tested on the car)** – **Car position missing**: status has no `lastPosition`
   at all (privacy state None).
   → Also call `/connectedcar/v4/user/vehicles/{id}/lastPosition` and use it.
   Result on the Mokka-e: lastPosition answers **HTTP 404** → asked again only after a new trip or 24 h.
   → Trips have `_links.wayPoints`: the newest trip's way points are fetched and saved; the last one is the car
     position (at stoppedAt, "from the last trip"); a newer phone GPS point wins. Trip details draw the route
     from them when the phone has none. Log: "wayPoints: HTTP <code>, <N> points (try n/5)"; failed or empty
     answers are tried again in the background (15 min, 5 times per trip); opening a trip always asks again.
     All car data shows the position's source. Route recording on the phone logs each step ("route: …").
4. ✅ **Partly done (2026-11-27, not yet tested on the car)** – shown as "probably on" when unplugged, awake and in a
   schedule window or after a pre-heat from EV Remote (see UPSTREAM.md). The Stop switch on error 1 is not done.
   **Pre-heating by timer not shown when unplugged**: `preconditioning.airConditioning.status`
   stays `Disabled` while the car heats (battery drops ~3 kW, car awake). Plugged in it shows Enabled.
   → If "already running" (error 1) comes back, switch button to Stop. Stop sends `deactivate`
     (error 4 = wasn't running, harmless). Optionally infer "heating by timer" from: timer window
     (≤45 min before set time), unplugged, car awake, level dropping.
5. ✅ **Done (2026-11-27, not yet tested on the car)** – **Scheduled charging start**: allow changing the time (not only
   turning it off), like myOpel. → Charging screen: time with a pencil (24 h), "Delayed start" switch (immediate /
   delayed), the same time as the charging window start (switch locked while the window is on); "Stop charging" only
   while charging. "immediate" is forgotten when the car reports a time set elsewhere. The window's "From" is always
   the scheduled start time (one value, also when the window is off).
   "Delayed start" shows what the car does: waiting when plugged in (also set with the car's buttons) → on, charging →
   off; on while charging stops it until the scheduled time. Big "Charge now | Delayed" switch on Charging and Home.
6. ✅ **Done (2026-11-27)** – **Command & error log** saved on the phone (`command_log.txt`, last 500 lines, with date).
7. ✅ **Done (2026-11-26/27)** – Hide (don't delete, to keep upstream merges easy): trip planner, Android Auto entry, Norway
   prices/NOBIL, YouTube screen. Planner search bar and the Android Auto settings are hidden; NOBIL only shows with a
   built-in key (none). Android Auto itself stays registered: route recording needs it (see UPSTREAM.md).
8. Waking the car too often triggered the car's 12 V "energy saving mode" – keep wakes minimal
   outside charging. `battery.voltage` (12 V indicator) was 64.
   ✅ (2026-11-27) Car data card warns below 60 %: "12 V battery low – avoid waking the car often";
   ChargeWake never wakes an unplugged car.
9. ✅ **Done (2026-11-27)** – Pre-heat refused when unplugged at low battery: warning (orange, small) on the
   Climate card, Climate screen and pre-heat schedules when unplugged and < 55 %; the reason is added to the
   error when Pre-heat fails in that state.

Upstream: https://github.com/thocubeat/lionflow – see UPSTREAM.md for the last synced commit.
