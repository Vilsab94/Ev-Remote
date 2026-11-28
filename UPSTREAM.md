# EV Remote ↔ LionFlow

EV Remote is a personal build of [LionFlow](https://github.com/thocubeat/lionflow) by thocubeat (GPL-3.0),
customised for an Opel Mokka-e. All credit for the original app goes to its author.

## Upstream sync log

- Based on LionFlow `7f58dad011a5` (2026-11-26): "Demo for any kind of car and brand, and speed in Android Auto"
- Based on LionFlow `7b44e55` (2026-11-27), picked by hand (no merge: upstream moved the app into `Android/`,
  paths `Android/app/...` → `app/...`). Taken:
  - 4eb9770 + c8703ec: `fixLevel` removed (battery % is always the car's own), `fixRoof` added (sunroof reported
    open on cars without one); our `applyBatterySize`/SoH kept in `ChargePower.record()`.
  - c8703ec: `ChargeLimit.overLimit` gain also counts the report's age (up to 10 %).
  - 4eb9770: `ChargePower.scheduledStart()`/`rememberStartMode()` – after "Turn off" the scheduled start shows as off
    (RemoteControl.charging, ChargingClimate, ChargeLimitCard `reachAt` only with our limit on, Home `chargingLine`).
    EV Remote also uses it in `ChargeWake.plan()`, so no wake-up for a start that was turned off.
  - 36fdff4: car name "My Peugeot" → "My Opel" etc. on other brands (Settings.kt `withBrandName`/`brandName`).
  - f075a55: `model/LockHold.kt` + `LockHoldTest`, used in AppViewModel `applyHolds`/`lockCar`.
  - c8703ec: Updater `result`/`installWithSystem` (Android's installer after 30 s without an answer),
    `file_paths.xml` `updates/`; currency choice (`ChargeLog.CURRENCIES`, Settings → Units, ChargeLogSection, FuelStatsUi).
  Not taken: c8703ec StatusWorker wake-ups (our `ChargeWake` does it), the "energy" fallback in VehicleStatus/energyLog,
  "Outside" from the weather, mild-hybrid texts, `remember(…, carLevel)` "car says"; mph (36fdff4 Root/HomeCards/
  TripsService), Android Auto (CarInfoScreen), iOS, new languages (Finnish, Danish, l10n.tsv, wear strings),
  README/PRIVACY.
- Checked up to LionFlow `99ce4ba` (2026-11-27). Taken: 99ce4ba MapScreen car symbol (car seen from above, arrow only
  with a heading; the Mokka-e sends none). Not taken: d187709 (lastPosition: ours already asks it, with the 404 rule;
  petrol cars without fuel data), iOS.

## EV Remote changes (kept small so LionFlow updates merge easily)

- Name: user-visible "LionFlow" → "EV Remote" in Kotlin string literals and `assets/l10n.tsv`
  (package names, class names, log tags and the backup marker file stay as in LionFlow,
  so LionFlow backups can be restored into EV Remote).
  Wordmark "Lion"+"Flow" → "EV"+" Remote" (accent colour) in `Wordmark()` (Components.kt) and two places
  in Home.kt; widget title in `layout/widget_car.xml`; Wear `app_name`/`no_phone` in all `wear/.../values*/strings.xml`;
  shared file names `evremote-backup.zip`, `evremote-trips.csv`. Left as is: User-Agent/API client names, wear paths.
- Battery size (PLAN.md item 1): `AppSettings.batteryKwh` = usable capacity of the NEW battery, default
  `DEFAULT_BATTERY_KWH` = 46 (Settings.kt, pref key `batteryNewKwh`; no "automatic" any more).
  Usable now = new × SoH: `ChargePower.userKwh()`. SoH comes only from `battery.health.capacity`
  (`VehicleStatus.batteryHealthPct`, later: capacity, else resistance – see SoH below); the last one is saved (`soh` per VIN) and used
  when the car leaves it out; 100 % until one has been received. `ChargePower.applyBatterySize()` in `record()`
  replaces the server's `residualKwh`/`capacityKwh` with level % × usable for electric cars and fills a missing
  SoH from the saved one, so charging kW, time-to-limit estimates (`usableKwh()`), the charge log and trips
  logged from the car use it. Server trips: `Trip.parse()` multiplies kWh and kWh/100 km by
  `ChargePower.tripFactor()` = usable / server capacity (remembered as `srvCap`). `ChargeLog` always uses
  Δ% × usable (also re-computed for saved sessions on load); `EstimatedHealth` is hidden. Settings shows
  "46 kWh × SoH 93 % = 42,8 kWh usable" (`batteryLine()` in SettingsScreen.kt).
- Car position (PLAN.md item 3): `StellantisApi.status()` also calls
  `GET /connectedcar/v4/user/vehicles/{id}/lastPosition` (same headers/client_id) via `addLastPosition()` and puts
  the raw answer (or `{"error": "HTTP 404"}`) into the status JSON as `evRemoteLastPosition`
  (`VehicleStatus.LAST_POSITION_KEY`). It never throws. `VehicleStatus.parse()` uses the status's own `lastPosition`
  first, else that answer (`positionFeature()`: Feature or FeatureCollection, [lon, lat, alt]) for lat/lon/heading,
  and the new `positionAtMillis` (properties.updatedAt). Parked card (HomeCards.kt) and the map's car panel
  (MapScreen.kt) show "· updated 2 h ago". "All car data" raw text lists the lastPosition answer separately.
  The command & error log gets "lastPosition: HTTP <code>" without coordinates (`StellantisApi.log`, set in
  LionFlowApp.kt; logged when the result changes, else at most every 30 min).
  A 404 (`ApiException.noData`) is remembered per car (`ev_remote` prefs, `lastPos404_<id>` = "millis,odometer") and the
  request is sent again only after a new trip (odometer higher) or 24 h; a 200 clears it. Fallback position (`AppViewModel.withFallbackPosition()`, applied after status, trips and
  routes load): end of the newest trip with coordinates (`Trip.endLat/endLon`, read by `Trip.latLonOf()` from
  `Trip.END_KEYS`: endPosition, stopPosition, arrival… as GeoJSON or lat/lon), else the last point of the newest
  phone route (route tracking). `VehicleStatus.positionSource` = "trip" / "phone"; `positionAge()` in HomeCards.kt
  shows "from the last trip, 2 h ago" / "from the phone's GPS, 2 h ago". The log lists the newest trip's field
  names (no values) and how many trips have an end position.
- Battery maths, one capacity everywhere: SoH is also saved as `soh_any` (the per-VIN key isn't known on some
  background starts, which gave 46 kWh one time and 46 × SoH the next). `ChargePower.adjusted()` is used where a
  status is parsed again without `record()` (AppViewModel.updateMildHybridStatus). Charging samples, report
  history and car-trip snapshots saved with older maths are dropped once (`evCapV3`). History lines show usable/soh.
- Overpass: removed the maps.mail.ru mirror (Services.kt `OsmChargers.ENDPOINTS`); overpass-api.de,
  overpass.private.coffee, overpass.kumi.systems remain.
- SoH (2026-11-27): `VehicleStatus.sohOf()` – energies[] Electric → extension.electric.battery.health.capacity,
  else energy[] → battery.health.capacity; if no capacity, health.resistance (both places, `sohSourceOf()`; the Mokka-e
  sends only resistance = 93, an OBD reader confirmed 93 %). Source saved (`ChargePower.sohSource()`, `sohSrc`). `healthLog()` line logged
  on every `StellantisApi.status()`. Saved with date (`ChargePower.sohAtMillis()`, keys `sohAt`/`sohAt_any`);
  `healthText()` in HomeCards.kt shows "93 % · resistance · received <date>" (Charging screen, All car data). Settings line
  "46 kWh × 93 % = 42,8 kWh".
- Trips: `model/OutsideTemp.kt` keeps environment.air.temp with its time (from `ChargePower.record()`); trip list
  shows the temperature nearest each trip start and the average in the header; kWh/100 km labelled
  "from battery", "≈" + lighter grey when the battery dropped < 3 % (TripsService.kt). Trip details: "Energy used" shows
  "1.4 kWh · −3 %" like the list.
- wayPoints: `Trip.wayPointsHref` (_links.wayPoints.href); `StellantisApi.wayPoints()` (token only to the API host);
  `model/WayPoints.kt` parses/saves points (filesDir/waypoints) and the debug text for All car data;
  `AppViewModel.tripWayPoints()` / `loadNewestWayPoints()`; `withFallbackPosition()` uses the newest trip's last
  way point at stoppedAt unless the phone's GPS point is newer; until the newest trip's points arrive, the newest
  trip with saved points. A failed answer (5xx, error, 0 points) is not saved: `WayPoints.mayTry/noteTry` ask again
  when trips load, every 15 min at most, 5 tries per trip (`wpTry_<id>`). TripDetail draws the route from way points
  and always asks again when opened (`tripWayPoints(t, userAsked = true)`); points for a newer trip move the car position.
- Route recording (`route/RouteService.kt`): Bluetooth connect/disconnect, start, refusal, missing permission and
  saved/not kept routes go to the command & error log via `RouteService.log()` (no positions).
  All car data → Position → Source: lastPosition / trip <date> / phone GPS.
- Charging wake-ups: `work/ChargeWake.kt` (`plan()` after every status from AppViewModel and StatusWorker; exact alarm →
  `ChargeWakeReceiver` → expedited `ChargeWakeWorker`; `Waker.wakeAndWait()`). StatusWorker's own 10/4-min
  follow-up only runs without remote control. Manifest: SCHEDULE_EXACT_ALARM, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
  FOREGROUND_SERVICE_DATA_SYNC, the receiver, and WorkManager's SystemForegroundService with type dataSync (expedited
  work on Android 8–11). Battery optimization dialog once (Root.kt, `askedBatteryOpt`).
  RemoteControl's MQTT callback keeps a wake-up id after 903 (and calls the waiter there): upstream drops it at 903, so
  the car's answer "0" is logged as "? → 0" and `wakeAndWait()` timed out. Unanswered wake-ups retry only while charging.
- Pre-heat on grid power: `work/GridPreheat.kt` (`plan()` next to `ChargeWake.plan()`; exact alarms → `GridPreheatReceiver`
  → `GridPreheatWorker`, which wakes + reads every 5 min until pre-heating is off; `ChargeWake.plan()` skips while
  `ownActive()`; setting `gridPreheatSchedules` raw, on unless "0"). `AppViewModel.preconditioning()` asks
  (`askGridPreheat`, dialog in Root.kt) and `preconditioningSend(on, grid)` sends charging first / stops it after.
  `ChargeLimit.delayTime()` is internal; `overLimit()` adds `OVER_LIMIT` while active; `ChargeWindow.check()` doesn't stop it.
  Toggle at the end of the PreheatSchedules card.
- Home (phone): `HomeLayout.PHONE` has "climate" after "battery" (`ClimateCard` in Home.kt, full width; `withClimate()`
  puts it after the battery in a saved order); "parked" is gone and "tripsClimate" draws `ParkedTile` (HomeCards.kt) +
  `TripsTile`. Upstream `ClimateTile` replaced by `ClimateCard`; `ParkedCard` is no longer used.
- Route recording: a `rec-*.part` left by a recording that was cut off (app updated / killed while driving) is kept up
  to its last point by `RouteService.saveLeftovers()` on the next start and on Bluetooth disconnect ("cut off earlier").
- "Pre-heating finished" (StatusWorker): only when the last check (snapshot "t") saw it on under 20 min ago and the car
  hasn't moved (odometer); GridPreheat also sends it (id 3) when its 5-min checks see pre-heating end.
- Command log: `RemoteControl.logFile` (filesDir/command_log.txt, `LOG_MAX` = 500 lines, "dd.MM HH:mm:ss").
- Pre-heat: `PreheatLowBatteryNote()` (ChargingClimate.kt) on the Home climate tile, Climate screen and
  PreheatSchedules; `AppViewModel.preheatLowBattery()` adds the reason to a failed Pre-heat.
- 12 V: Car data card (HomeCards.kt) warns when `battery.voltage` as % is below 60.
- Logo: lightning bolt (#F5C400, outline #1A1A1A) on white – `ic_lion_foreground.xml`,
  `ic_lion_mono.xml`, `ic_notification.xml`, `ic_w_lion.xml`, `LionLogo()` in Components.kt
  (file/function names kept). `launcher_bg` = white.
- Colours: lime green accent → yellow #F5C400 (Theme.kt, colors.xml, car/ui/Kit.kt, widget_wave.xml);
  pre-heat orange → coral #FF7F50 so it differs from the yellow.
- Hidden (code kept): trip planner search bar on the map (`if (false && electric)` in MapScreen.kt),
  Android Auto settings section and Watch/Android Auto home editors in Settings.
- Removed from Settings: "Support LionFlow" (Ko-fi) and "Send feedback" (mailed the LionFlow author);
  added "Source code" link and a LionFlow credit in the footer.
- No usage ping (`countActiveDevice`), updates from Vilsab94/Ev-Remote, package `lt.vilsab.evremote`.
- Privacy (2026-11-27): manifest no longer asks for RECORD_AUDIO (voice search only in Android Auto;
  `tools:node="remove"` so a library can't add it back). Android Auto (meta-data, `car.LionFlowCarService`,
  NAVIGATION_TEMPLATES/ACCESS_SURFACE, CAR_SPEED, CAR_FUEL) was removed too, then put back the same day: without it
  route recording no longer started on its own when the phone connected to the car (most likely Android 12+ allows
  the background location service only while Android Auto is bound to the app). Kept on purpose: ACCESS_BACKGROUND_LOCATION,
  FOREGROUND_SERVICE_LOCATION, BLUETOOTH/BLUETOOTH_CONNECT – used by Route tracking (Settings, trip maps and the
  phone-GPS car position); they are only asked for when the user turns it on.
- Error texts use `Brands.current.display`/`.app` instead of a fixed "Peugeot"/"MyPeugeot": `Http.describeHttpError`,
  RemoteControl (`mapCode`, MQTT errors, "Sent to …"), AppViewModel (`NO_REMOTE` is now a getter, session expired,
  servers down, no car), CarWidget refresh texts. UI texts already go through `Brands.apply` in `L10n.tr`.
- SoH guard (2026-11-27): the Mokka-e sends health.resistance only, sometimes 100 (not worked out) and sometimes 93
  (real, OBD). `ChargePower.applyBatterySize()` takes a higher value only when the saved one is older than 60 days
  (`SOH_KEEP_MS`); else logs "health ignored: 100 (kept 93)" (`ChargePower.log`, set in LionFlowApp) and uses the
  saved SoH, so usable stays 46 × 0.93.
- Scheduled start (Opel API: only "immediate" or "delayed" + time in /VehCharge): `rememberStartMode(immediate, h, m)`
  keeps "immediate,H:M,sentMillis"; `scheduledStart()` forgets it when a car report >15 s after the command has another
  time (set in the car or myOpel). `startTime()` = the car's time in either mode. ChargeWake uses `scheduledStart()`.
  Charging screen (`ScheduledStartSection` in ChargingClimate.kt): time with a pencil (`LfIcons.Pencil`, 24 h
  `ClockDialog`, now internal in ChargeLimitCard.kt) → `AppViewModel.setStartTime()` (same mode; with the charging window
  on it moves the window start via `ChargeWindow.applyToCar`); "Delayed start" switch → `setDelayedStart()` (locked on,
  "Set by charging window", while the window is on). `holdStart` in `applyHolds` keeps the sent time until the car
  reports it. The window's From is always the car's start time: `AppViewModel.syncWindowFrom()` moves it to a time reported
  >15 s after our last /VehCharge (`ChargePower.lastStartSentAt()`); the pencil moves it too, also with the window off.
  "Delayed start" shows the car's state (`ChargePower.delayedNow()`: plugged + Stopped + start time → on, charging →
  off, else what was last sent; `learnStartMode()` forgets "immediate" when the car waits >5 min after it);
  `holdCharging` keeps the asked-for status up to 3 min.
  The switch is `DelayedStartControl` (ChargingClimate.kt): a big "Charge now | Delayed · 04:30" control on the Charging
  screen and on Home's battery card (and ChargingSummary), replacing the small toggle and "Stop charging"; Home's
  `chargingLine()` shows "Plugged in · delayed until 04:30".
- Pre-heating unplugged (PLAN item 4): the car reports "Disabled" while it heats. `ChargePower.fixPreheat()` sets
  `VehicleStatus.preheatGuess` (`preheating` = reported or guessed) when unplugged, parked, reported in the last 15 min
  and in a schedule window (−25…+10 min) or ≤30 min after a pre-heat from EV Remote (`rememberPrecond`, not after a
  stop). UI, widget, watch and the finished notification use `preheating`; schedule saves still send the reported value.
  `AppViewModel.preheatAnswer()`: /ThermalPrecond error 1 on Pre-heating ("already running") → shown on (holdPrecond,
  `rememberPrecond(true)`), so the button becomes Stop; error 4 on Stop ("wasn't running") → shown off. Not a failure.
  "Turn off"/`clearScheduledStart` and "Charge now" removed; Charging and Home show "Stop charging" only
  while charging.
- When merging upstream: keep our README.md, UPSTREAM.md, PLAN.md and .github/workflows/build.yml.
