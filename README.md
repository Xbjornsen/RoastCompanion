# RoastCompanion

A native Android app for monitoring coffee roasts on the **Gene Cafe CBR-101** drum roaster. Uses the device microphone and real-time audio analysis to detect first crack and second crack events, alarm you at second crack, and calculate carryover roast development during the CBR-101's cooling cycle.

Built for personal use by an experienced home roaster — no onboarding, no fluff.

---

## Features

### Roast Monitoring
- **Start Roast** begins a session, starts the roast timer, and activates microphone monitoring
- Real-time **mic level meter** (scrolling bar chart) so you can see the audio environment is being captured
- Running **session timer** displayed prominently throughout the roast

### First Crack Detection
- Continuously analyses mic input for the sharp transient bursts characteristic of first crack
- Uses a **rolling RMS noise floor** estimate (lower 70th percentile of the last 5 seconds) so the threshold adapts to your environment — fan noise, ambient hum, and low-frequency roaster rumble are filtered out
- Requires N transients within a 3-second window before confirming (configurable, default 3) — prevents false positives from single loud events
- On detection: **visual alert** (amber card animation) + vibration + Snackbar notification
- Logs **FC start timestamp** to the session
- Continues monitoring; when a configurable quiet period elapses with no further transients (default 8 seconds), first crack is marked **complete** and the FC end timestamp + duration are logged

### Second Crack Alarm
- After first crack completes, monitoring continues with the same detection engine
- On second crack detection: **loud device alarm** (uses the phone's default alarm sound at alarm volume) + vibration pattern + persistent Snackbar with a "Start Cooling" action
- Logs **SC detected timestamp**

### CBR-101 Carryover Cooling Calculator
- When **Start Cooling** is tapped, a bottom sheet opens showing a countdown and estimated additional roast development
- The CBR-101 drum continues tumbling with residual heat after cooling is triggered; beans keep developing for 30–60 seconds depending on roast level
- Default carryover window: **45 seconds** (configurable 15–120s)
- Displays estimated roast colour label as the countdown progresses: City+ → Full City → Full City+ → Vienna
- Circular progress indicator shows remaining fraction

### Roast Log
- Every session is saved to a local **Room database**
- Log screen shows all sessions newest-first: date, total duration, FC timestamp, second crack flag
- Tap any session for a full **timeline detail view**: Start → FC Start → FC End → SC → Cooling Start → End, plus total duration and notes
- Swipe-to-delete with undo
- Add freeform **notes** to any session (edit via FAB)

### Settings
All detection parameters are adjustable in-app via sliders:

| Setting | Default | Range | Description |
|---|---|---|---|
| Detection Sensitivity | 3.5× | 1.5–10× | Spike must exceed N × ambient noise floor |
| FC Quiet Period | 8s | 3–30s | Silence after FC activity before FC is marked complete |
| Carryover Duration | 45s | 15–120s | Estimated continued development during cooling |
| Min Transients (FC) | 3 | 1–10 | Transients required in 3s window to confirm first crack |
| Min Transients (SC) | 2 | 1–5 | Transients required in 3s window to confirm second crack |
| Alarm Sound | On | — | Play device alarm on second crack |
| Vibration | On | — | Vibrate on crack events |

---

## Audio Detection Algorithm

The detection engine runs entirely on-device with no network dependency.

**Capture:** AudioRecord API at 44100 Hz, 16-bit PCM mono. Samples are read in 50ms windows (2205 samples) giving 20 analysis frames per second.

**Noise floor:** A rolling deque of the last 100 RMS frames (~5 seconds) is maintained. The ambient estimate uses the **lower 70th percentile** of that window — this excludes crack transients from inflating the baseline, so detection sensitivity doesn't degrade once cracking starts.

**Transient detection:** A frame is classified as a spike when:
```
currentRMS > ambientRMS × thresholdMultiplier
```

**Debounce:** A rolling 3-second window counts transients. A phase transition only fires when the window accumulates ≥ N spikes (N = min transients setting). The window resets after each confirmed transition or after 3 seconds of silence. This prevents single loud events (a dropped portafilter, a door slam) from triggering a false positive.

**State machine:**
```
IDLE → [Start Roast] → MONITORING
MONITORING → [N transients in 3s] → FIRST_CRACK_ACTIVE
FIRST_CRACK_ACTIVE → [quiet period elapsed] → FIRST_CRACK_COMPLETE
FIRST_CRACK_COMPLETE → [N transients in 3s] → SECOND_CRACK_ACTIVE
SECOND_CRACK_ACTIVE → [Start Cooling tapped] → COOLING
```

The ambient noise floor is only updated from **non-spike frames**, so bursts of crack activity don't raise the threshold and suppress subsequent detections.

---

## Usage Tips

- **Phone placement:** Hold the phone close to the chaff collector outlet or the front panel of the CBR-101. The chaff collector is enclosed so crack sounds are somewhat muffled — closer is better.
- **Threshold tuning:** On first use, start a roast session and watch the level meter before beans crack. If the bar chart shows frequent tall spikes just from fan/motor noise, raise the sensitivity multiplier in Settings. If it's very quiet, lower it.
- **Environment:** A quiet roasting space helps. The CBR-101's motor noise sits mostly in the low-frequency range; the 70th-percentile ambient filter handles steady background noise well but loud music or conversations near the machine can cause false positives — raise min transients to 4–5 in noisy environments.
- **Second crack:** SC on the CBR-101 is faster and less dramatic than FC. If you've raised the threshold to filter FC ambient noise, you may want to lower min transients for SC (setting: Min Transients SC) so it doesn't miss the first wave.

---

## Tech Stack

| Component | Library |
|---|---|
| Language | Kotlin |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 34 (Android 14) |
| UI | Material Design 3, ViewBinding |
| Navigation | Navigation Component 2.7.7 + Safe Args |
| Audio | AudioRecord API |
| Database | Room 2.6.1 |
| Settings | DataStore Preferences 1.1.1 |
| DI | Hilt 2.51.1 |
| Async | Coroutines + Flow |
| Charts | MPAndroidChart 3.1.0 |

---

## Project Structure

```
app/src/main/java/com/roastcompanion/
├── audio/
│   ├── AudioAnalyzer.kt        # AudioRecord loop, state machine, flow outputs
│   ├── TransientDetector.kt    # RMS computation, rolling ambient, spike detection
│   ├── RoastPhase.kt           # State machine phases enum
│   └── CrackEvent.kt           # Sealed class: FC started/ended, SC started
├── data/
│   ├── db/                     # Room database, DAO, RoastSession entity
│   ├── repository/             # RoastRepository (single source of truth)
│   └── preferences/            # DataStore-backed UserPreferences
├── service/
│   └── RoastMonitorService.kt  # Foreground service — owns AudioRecord
├── di/                         # Hilt modules (DB, Audio)
├── model/
│   └── CookingCarryover.kt     # Pure carryover calculation functions
├── ui/
│   ├── roast/                  # Main roast screen + ViewModel + carryover sheet
│   ├── log/                    # Session list, detail, adapter
│   └── settings/               # Settings screen + ViewModel
└── util/                       # NotificationHelper, PermissionHelper, TimeFormatter
```

The foreground service owns the `AudioRecord` instance so mic monitoring continues when the screen is off or the app is backgrounded. `AudioAnalyzer` is a Hilt singleton shared between the service (which writes audio data to it) and the ViewModel (which reads its `StateFlow`/`SharedFlow` outputs) — no service binding required.

---

## Building

1. Clone the repo and open in Android Studio (Hedgehog or later)
2. Let Gradle sync and download dependencies
3. Connect an Android device (API 26+) — emulators have no mic input
4. Run — mic permission is requested on first "Start Roast" tap

> **Alarm sound:** The app uses the device's default alarm ringtone via `RingtoneManager`. No audio file needs to be bundled. Make sure your phone has an alarm sound configured in system settings.

---

## Permissions

| Permission | When requested | Purpose |
|---|---|---|
| `RECORD_AUDIO` | First "Start Roast" tap | Microphone access for crack detection |
| `POST_NOTIFICATIONS` | First "Start Roast" tap (Android 13+) | Foreground service persistent notification |
| `FOREGROUND_SERVICE_MICROPHONE` | Granted at install | Allows mic use in foreground service |
| `VIBRATE` | Granted at install | Vibration alerts on crack events |

---

## Limitations & Known Considerations

- Detection is amplitude-based transient counting, not ML or frequency-domain classification. It works well in reasonably quiet environments but is not infallible. Always watch your beans — the app is a monitoring aid, not a replacement for attention.
- The CBR-101's enclosed chaff collector dampens crack sounds more than open-drum roasters. A phone pressed against the machine body picks up more than one sitting on a nearby bench.
- Carryover calculation is a configurable linear estimate, not a thermometric model. The 45-second default reflects typical CBR-101 cooling behaviour at City+ to Full City+ roast levels. Adjust based on your own observations.
- If the app is killed by the OS (extreme memory pressure), the foreground service may be restarted with an incomplete session. In-progress session data is saved to the database incrementally as each event fires, so FC/SC timestamps already logged will be preserved.
