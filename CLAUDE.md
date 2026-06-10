# RoastCompanion — Claude Context

Native Android (Kotlin) app for monitoring **Gene Cafe CBR-101** coffee roasts.
Listens with the phone mic, detects first/second crack acoustically, alarms at
second crack, and tracks the CBR-101's ~45s cooling carryover. Personal-use
tool for an experienced home roaster — function over onboarding.

## Working agreements with the owner

- **Do not push to GitHub without explicit approval.** The owner reviews UI
  changes on-device first and says when to push. This has been a standing rule.
- The owner tests on a physical device over adb (device id `3B15C300WQX00000`).
  After meaningful changes: build, `adb install -r`, launch, let them look.
- For visual/design decisions, the loop that works: build throwaway HTML
  mockups, let the owner pick, then implement the chosen design in Android XML.
  Delete mockups after applying.
- Plain English in the UI. No engineer-speak ("Threshold Multiplier" → "Crack
  Sensitivity"). No tick labels under sliders.

## Build (Windows, this machine)

- **`gradlew.bat` is broken** — `gradle/wrapper/gradle-wrapper.jar` is missing.
  Use the cached distribution directly:
  ```powershell
  $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'   # NOT "Android Studio" — that JBR install is corrupt
  & 'C:\Users\User\.gradle\wrapper\dists\gradle-8.7-bin\bhs2wmbdwecv87pi65oeuq5iu\gradle-8.7\bin\gradle.bat' assembleDebug
  ```
- Install/launch:
  ```powershell
  $adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
  & $adb install -r app\build\outputs\apk\debug\app-debug.apk
  & $adb shell am start -n com.roastcompanion/com.roastcompanion.ui.MainActivity
  ```
- Crash debugging: `adb logcat -b crash -d`.

## Stack

Kotlin · min SDK 26 / target 34 · Hilt 2.51.1 · Room 2.6.1 (KSP, schema via
`ksp { arg("room.schemaLocation", ...) }` — the `room {}` DSL is NOT applied) ·
DataStore Preferences · Navigation Component + Safe Args · Material 3 ·
MPAndroidChart 3.1.0 (JitPack repo in settings.gradle.kts).

## Architecture

```
audio/      TransientDetector (RMS + ambient floor), SpectralGate (FFT),
            AudioAnalyzer (@Singleton state machine), RoastPhase, CrackEvent
service/    RoastMonitorService — foreground service (microphone type), owns AudioRecord
data/       Room (RoastSession entity/dao), UserPreferences (DataStore), RoastRepository
ui/         MainActivity (BottomNav + NavHost)
  roast/    RoastFragment + RoastViewModel (timer, alerts, level chart), CarryoverFragment (dialog)
  log/      RoastLogFragment (history + search/filter/swipe-delete), SessionDetailFragment (editable notes)
  settings/ SettingsFragment + ViewModel
  guide/    GuideFragment — static "Roasting 101" reference (CBR-101 quick start, roast levels)
```

**Key design fact:** `AudioAnalyzer` is a Hilt `@Singleton`. The foreground
service writes audio into it; ViewModels collect its StateFlows directly.
**There is no service binding** — don't add one.

## Crack detection (3 gates, all must pass)

A loud frame only counts toward a crack if:

1. **Warmup** — first 3s of a session build the ambient noise floor; nothing
   detects during warmup (`TransientDetector.MIN_WARMUP_FRAMES`).
2. **Amplitude** — RMS > ambient × multiplier (default 3.5, user setting).
   Ambient = mean of the lower 70th percentile of last 100 frames, only
   updated from non-spike frames.
3. **Spectrum** — `SpectralGate`: 2048-pt FFT, ≥45% of audible energy must be
   in 2–9 kHz (cracks are high-frequency pops; voice/fan/thuds are low).

Plus a **time gate**: transients ignored before "Earliest First Crack"
(default 4 min, user setting `minFcTimeMin`) — the CBR-101 never reaches FC
earlier. And a **pattern gate**: FC confirmation needs `minTransientsFc` crack
frames spread over ≥4s within a 15s window (real FC rolls like popcorn; a
burst of clicks doesn't qualify). SC uses a 10s window, no span requirement.

State machine: IDLE → MONITORING → FIRST_CRACK_ACTIVE → (quiet period) →
FIRST_CRACK_COMPLETE → SECOND_CRACK_ACTIVE → COOLING.

## Design system — "Dark Coffee Lab"

One dark theme used in BOTH light and night mode. **Never** create a
`values-night/themes.xml` that re-declares `Theme.RoastCompanion` with itself
as parent — that circular reference crashed the app once already. There is
currently no `values-night/` folder, intentionally.

Palette (in `values/colors.xml` as `lab_*`):
- Background `#150D08`, cards `#241710`, borders `#382519`
- Text `#FBF4E8`, muted `#A89178`, dim `#7A5B40`
- Accent amber `#FF9544` (gradient pair `#C8541A`), SC/danger red `#E84A3A`,
  cooling mint `#58FFA9`
- Monospace (`fontFamily="monospace"`) for all numeric/timer values
- Cards: `bg_card_surface` drawable (18dp radius, 1dp border), not MaterialCardView
- Screens have a text header ("RoastCompanion" + small caps crumb), no toolbars
  except none at all — headers are part of each fragment's layout

Launcher icon: gradient bean (espresso→caramel→green) on `#1F0900` background,
in `ic_launcher_foreground/background.xml`. The owner iterated on this a lot —
don't change it without asking.

## Gotchas

- `RoastLogFragmentDirections` is **generated** by safe-args; never create it
  manually.
- RecyclerView version predates `bindingAdapterPosition` — use
  `adapterPosition` with `@Suppress("DEPRECATION")`.
- SettingsFragment uses an `updatingFromVm` flag to stop slider listeners
  firing during programmatic updates — keep that pattern for new settings.
- Undo for swipe-delete must call `repository.restoreSession(session)` (full
  entity re-insert), not `createSession(startTimeMs)` which drops crack data.
- `gradle.properties` needs `android.useAndroidX=true` (safe-args fails without).
- PowerShell 5.1 `Set-Content -Encoding utf8` writes a **BOM** — it silently
  broke `keystore.properties` parsing once (first key became `﻿storeFile`,
  release APK came out unsigned). Write config files BOM-free.
- Piping values into `gh secret set` from PowerShell appends a newline that
  breaks `base64 -d` and keystore passwords in CI — always use
  `gh secret set NAME -b $value` instead.
- Room now uses a real `MIGRATION_1_2` in `DatabaseModule` — the old
  `fallbackToDestructiveMigration()` is gone on purpose (it would wipe roast
  history). Add proper migrations for future schema changes.

## Release pipeline / versioning

- Version lives in `app/build.gradle.kts` (`appVersionName`/`appVersionCode`,
  scheme major*10000+minor*100+patch). CI overrides both from the git tag.
- Tag `vX.Y.Z` + push → `.github/workflows/release.yml` builds a **signed**
  APK (provisioned Gradle 8.7, not the broken wrapper) and publishes a GitHub
  Release. See `RELEASING.md`.
- Signing: `release.jks` + `keystore.properties` in repo root, **gitignored**
  (repo is public). GitHub secrets `KEYSTORE_BASE64/KEYSTORE_PASSWORD/
  KEY_ALIAS/KEY_PASSWORD` already set. Losing the keystore breaks updates.
- In-app updater: `update/UpdateChecker.kt` reads
  `api.github.com/repos/Xbjornsen/RoastCompanion/releases/latest` (public, no
  token), compares semver vs `BuildConfig.VERSION_NAME`, downloads the .apk
  asset to cache and fires the package installer via FileProvider. Settings →
  App → Check for Updates.
- First release install on the phone needs a one-time **uninstall** of the
  adb debug build (debug vs release signature mismatch).

## Current state / open items

- All UI on the Dark Coffee Lab theme; detection gates implemented; guide and
  editable notes done. Pushed through `a0c3996`; **v1.1.0 released** on GitHub
  (signed APK, in-app updater live). Still: never push without owner approval.
- Done: CSV export/import (Settings → Your Data, RFC-4180, dedupes
  on `startTimeMs`), Delete All History (confirm dialog), Keep Screen Awake
  (on by default, only while a roast is active), feedback loop (favourite ★ =
  reference roast shown as live FC/SC targets on the Roast screen with delta
  at FC; 1–5 cup rating in session detail; Favourites filter chip), release
  pipeline + in-app updater (above). DB schema v2.
- Not yet implemented (deferred): ML-based crack classification.
