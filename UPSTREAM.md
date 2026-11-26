# EV Remote ↔ LionFlow

EV Remote is a personal build of [LionFlow](https://github.com/thocubeat/lionflow) by thocubeat (GPL-3.0),
customised for an Opel Mokka-e. All credit for the original app goes to its author.

## Upstream sync log

- Based on LionFlow `7f58dad011a5` (2026-11-26): "Demo for any kind of car and brand, and speed in Android Auto"

## EV Remote changes (kept small so LionFlow updates merge easily)

- Name: user-visible "LionFlow" → "EV Remote" in Kotlin string literals and `assets/l10n.tsv`
  (package names, class names, log tags and the backup marker file stay as in LionFlow,
  so LionFlow backups can be restored into EV Remote).
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
- When merging upstream: keep our README.md, UPSTREAM.md, PLAN.md and .github/workflows/build.yml.
