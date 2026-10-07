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
MPAndroidChart 3.1.0 (JitPack repo in settings.gradle.kts). No TFLite (removed in v2 detection).

## Architecture

```
audio/      AudioAnalyzer (@Singleton state machine), RollDetector (FC/SC), FeatureExtractor
            (impulsiveness + v2 model features), TransientDetector/SpectralGate (level/diagnostics),
            RoastPhase, CrackEvent
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

## Crack detection (v2 — impulsive-rate roll detector)

Pipeline: `RoastMonitorService` reads 50 ms frames (2205 samples) → `AudioAnalyzer.processBuffer`
→ `FeatureExtractor.impulsiveness(frame)` → `RollDetector.push()` → phase/events.

- **Impulsiveness** (per frame): energy of the 2nd difference in 1 ms blocks; ln(max/median).
  A crack pop is 1–5 ms, so it is one block far above the rest; fan/drum noise is flat at
  that scale. (At 50 ms frame level FC pops are buried in fan/drum noise — every gate the
  v1 detector used, incl. the ML model, saw MORE crack-like frames before FC than during it.)
- **RollDetector** counts pops (impulsiveness > 3.5) per window and fires when the rate rises
  well above the roast's OWN recent baseline (absolute loudness varies ~25x between roasts):
  FC: 20 s rate ≥ max(1.7×median of the 2 min ending 20 s ago, +20) for 5 s, not before 4 min
  and not before ("Earliest First Crack" setting − 60 s).
  SC: from 90 s after FC (auto or manual tap), 10 s rate ≥ max(1.3×baseline(60 s), +3) for 3 s.
  FC end (informational): rate back near the pre-FC baseline for the quiet-period setting.
- **Manual FC tap** → `AudioAnalyzer.forceFirstCrack()` re-anchors FC (and SC timing) to now.
- Measured with `training_data/scripts/harness.py` on 19 labelled roasts:
  FC 15/19 within −30..+60 s, 0 early, 1 late, 3 missed; SC after auto FC 8/17, after a
  manual FC tap 11/17 within ±30 s, 0 early/late anywhere; empty-roaster run: no events.
  (Fixed pop_th 3.5 gave FC 11/19, SC-after-tap 9/17.) Tuned on the same 19 roasts — but
  neighbouring settings (percentile 98.5–99.25, windows 2–6 / 3–7 / 4–7.5 min) all land
  at FC 15–16/19, so it is not a knife-edge pick.
- Adaptive pop threshold (adapt_* in rolldet.py, ADAPT_* in RollDetector.kt): impulsiveness
  from 2–6 min sets this roast's bar = clamp(99.25th pct, 2.0, 3.5). Why: on "soft"
  recordings (sessions 2, 3, 10, 11, and 2026-10-07 on the OnePlus CPH2747) the background
  hiss is ~13 dB higher, so a real crack's 1 ms peak only reaches imp ~2–3 (owner-marked
  cracks: training_data/marks/2026-10-07_fc.tsv, median 2.5). At imp>2.2 that roast has
  ~5 pops/min before FC vs ~46/min during FC — the information is there.
- Crack Marker: `python training_data/scripts/make_marker.py <wav> 9:20 9:55` builds a
  self-contained page (waveform + median-normalised spectrogram, slowed looping playback,
  double-click to mark). Owner marks → `training_data/marks/*.tsv`.
- **Constants live in TWO places that must match:** `audio/RollDetector.kt` and
  `training_data/scripts/rolldet.py`. `RollDetectorTest` replays real traces to enforce it.
  Change → edit both → `python harness.py` → update the test's expected times.
- Settings "Crack Sensitivity" (pref `crack_sensitivity`, 1..5, default 3) scales the FC/SC
  margins: ratio' = 1 + (ratio-1)*k, absMin' = absMin*k, k = 1.6/1.3/1.0/0.8/0.6 (same table
  in rolldet.SENS_K). `harness.py --sensitivity N`: 1 → FC 5/18, 2 → 9/18, 3 → 11/18,
  4 → 12/18 (but SC 1 early), 5 → FC 4 early. The old min-crack-count sliders were removed;
  TransientDetector/SpectralGate (thresholdMultiplier, no UI) only drive level + diagnostics.

### ML status
The v1 TFLite classifier was removed: it never loaded on device (heap ByteBuffer — TFLite
needs a direct native-order buffer) and its on-device MFCCs didn't match training (int16 vs
float scaling put c0 ~36σ off; filterbank normalisation). The rebuilt pipeline keeps ML
viable for later: `features.py` (single source of truth) == `FeatureExtractor.kt`, pinned by
`FeatureExtractorTest` (golden vectors from `make_golden.py`); `train_v2.py --eval` scores per
roast, never per frame. With 18 labelled roasts a model did not beat the rule-based detector.

### Training data workflow
1. Record roasts with Settings → Record for Training; copy `training_*.wav/.json` into `training_data/raw/`.
2. Label by ear (`make_clips.py` → `clips/player.html`), add to `labels.py` GROUND_TRUTH.
3. `python extract.py` (pure numpy) → `features/*.npz`; `python harness.py` to score.
Training JSON v2 measures every time from the start of the recorded audio (`"clock":"audio"`);
v1 files mixed the pause-adjusted timer and session start, so their taps drift off the WAV.

## Alerts
Crack sound/vibration/notification are owned by the foreground service (`util/CrackAlarm`),
not the Roast screen, so they fire with the screen off. SC alarm loops until "Stop alarm"
(notification action / SC sheet / Start Cooling) or 2 min. Honours the Settings toggles.

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
- v2 detection (impulsive-rate RollDetector) + service-owned alerts on branch fix/ml-detection.

- Running git from the Cowork Linux shell: use `git --no-optional-locks` for read-only
  commands — a plain `git status` there can leave a stale `.git/index.lock` it can't delete.
- Debug install "Activity class ... does not exist" right after a successful install = the
  phone hasn't been unlocked since reboot. Unlock it and Run again.
