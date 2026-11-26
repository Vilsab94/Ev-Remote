# EV Remote – planned changes (Opel Mokka-e 2022)

Findings from testing LionFlow with this car (Nov 2026):

1. **Battery capacity is wrong on the server.** `capacity` = 32960 Wh, `residual` implies ~27 kWh full,
   real usable is ~43 kWh (46 kWh × SoH 93 %). The battery % (level) is correct.
   Trip kWh (`energyConsumptions[].consumption`) is just Δ% × ~33 kWh.
   → Compute energy from level % × user battery size (default 43 kWh); optionally calibrate a factor
     against the car's own trip display. Apply to trip kWh/100 km, charging kW, charge log kWh.
     The "Battery size" setting must override server residual/capacity everywhere.
   Sample: 28 km trip, car 17.1 kWh/100, LionFlow 10.0 kWh/100 (−9 %, 2.8 kWh).
2. **Charge limit (80 %) fails on 11 kW AC**: the car sends no status while charging (02:00 start,
   next report 05:00 at 100 %). Background check only reads the server.
   → While charging, wake the car (Waker.wake) before each check (~10 min, ~4 min near limit).
     12 V battery is fine then (DC/DC is running). Never wake often when not plugged in.
3. **Car position missing**: status has no `lastPosition` at all (privacy state None).
   → Also call `/connectedcar/v4/user/vehicles/{id}/lastPosition` and use it.
4. **Pre-heating by timer not shown when unplugged**: `preconditioning.airConditioning.status`
   stays `Disabled` while the car heats (battery drops ~3 kW, car awake). Plugged in it shows Enabled.
   → If "already running" (error 1) comes back, switch button to Stop. Stop sends `deactivate`
     (error 4 = wasn't running, harmless). Optionally infer "heating by timer" from: timer window
     (≤45 min before set time), unplugged, car awake, level dropping.
5. **Scheduled charging start**: allow changing the time (not only turning it off), like myOpel.
6. **Command & error log** should be saved on the phone (now memory only).
7. Hide (don't delete, to keep upstream merges easy): trip planner, Android Auto entry, Norway
   prices/NOBIL, YouTube screen.
8. Waking the car too often triggered the car's 12 V "energy saving mode" – keep wakes minimal
   outside charging. `battery.voltage` (12 V indicator) was 64.

Upstream: https://github.com/thocubeat/lionflow – see UPSTREAM.md for the last synced commit.
