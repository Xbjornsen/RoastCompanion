package com.roastcompanion.audio

import android.media.AudioFormat
import android.util.Log
import com.roastcompanion.data.preferences.UserPreferences
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioAnalyzer @Inject constructor(
    private val detector: TransientDetector,
    private val prefs: UserPreferences,
    private val crackClassifier: CrackClassifier
) {
    companion object {
        private const val TAG = "RC"
        const val SAMPLE_RATE = 44100
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        // 50ms window → 20 frames/second
        const val WINDOW_MS = 50
        val SAMPLES_PER_WINDOW = SAMPLE_RATE * WINDOW_MS / 1000  // 2205

        // First crack is a rolling series of pops (popcorn), not a burst. We
        // collect FC-classified pops over a rolling window and require a
        // *sustained cadence* — many pops spread across several sub-windows.
        // This is what lets us trust the sound instead of the clock: a real
        // roll is unmistakable; stray isolated noise (e.g. the 8-min CBR tick)
        // can't fake sustained popping.
        const val FC_WINDOW_MS = 15_000L
        // Sub-window (bucket) size. Pops must land in >= FC_MIN_BUCKETS distinct
        // buckets, i.e. be spread over time, not clustered in one instant.
        const val FC_BUCKET_MS = 5_000L
        const val FC_MIN_BUCKETS = 3
        // Pops needed to confirm FC *after* the earliest-FC time (normal roast).
        const val FC_MIN_POPS_NORMAL = 5
        // Pops needed *before* the earliest-FC time — a much stronger bar. This
        // is the mid-roast-start case (app started with beans already popping)
        // AND the false-positive-prone early window. A genuine roll clears it in
        // ~10-15s; the isolated early tick never does.
        const val FC_MIN_POPS_EARLY = 8
        // Mean model FC-confidence required for an *early* (pre-time-gate) fire.
        // Belt-and-suspenders on top of the pop count. Tunable — validate the
        // exact value against on-device probs before trusting it hard.
        const val FC_EARLY_MIN_CONFIDENCE = 0.50f

        // Second crack window (FC context already gates this phase)
        const val SC_WINDOW_MS = 10_000L

        // Absolute startup floor: the CBR-101's fan/motor/element spin-up makes
        // ~10s of crack-like noise when switched on (observed auto-firing FC at
        // a consistent ~13.6s, and in the empty-roaster recording too). FC can't
        // physically happen this early even on a mid-roast app start, so block
        // all FC confirmation for the first stretch. Unlike minFcTimeMs (a soft
        // confidence prior), this is a hard mute — but short enough not to hurt
        // a genuine mid-roast start (a real roll still fires ~10s after this).
        const val FC_STARTUP_GRACE_MS = 25_000L

        // Hard floor between FC start and SC: real second crack is ~1.5-3 min
        // after first crack. Without this, a lull in the FC roll ends FC early
        // and the continuing roll gets misread as SC seconds later (the cascade).
        const val MIN_FC_TO_SC_MS = 75_000L

        // SC pops are quieter than FC (≈2× ambient vs ≈7×). Use a lower
        // amplitude bar once we're listening for SC so quiet snaps still reach
        // the classifier, which then confirms the SC class by sound.
        const val SC_AMP_MULTIPLIER = 1.8f
    }

    private val spectralGate = SpectralGate()

    // ---- Frame look-back buffer (last 10 seconds at 20fps = 200 entries) ----
    // Written on the IO thread; read from the Main thread via peakRmsRatioInLastSeconds().
    private data class FrameSnapshot(val timestampMs: Long, val rmsRatio: Float)
    private val frameBuffer = ArrayDeque<FrameSnapshot>(200)
    private val bufferLock = Any()

    private fun recordFrame(now: Long, rms: Float) {
        val ambient = detector.ambientRms.coerceAtLeast(1f)
        val ratio = rms / ambient
        synchronized(bufferLock) {
            if (frameBuffer.size >= 200) frameBuffer.removeFirst()
            frameBuffer.addLast(FrameSnapshot(now, ratio))
        }
    }

    /**
     * Returns the highest rms/ambient ratio seen in the last [windowMs] milliseconds.
     * Called from the Main thread when the user confirms a crack event — gives a
     * retrospective reading of how loud the crack was relative to the ambient floor.
     */
    fun peakRmsRatioInLastSeconds(windowMs: Long = 5_000L): Float {
        val cutoff = System.currentTimeMillis() - windowMs
        return synchronized(bufferLock) {
            frameBuffer.filter { it.timestampMs >= cutoff }.maxOfOrNull { it.rmsRatio } ?: 0f
        }
    }

    // ---- Settings (updated from prefs before each session) ----
    @Volatile var thresholdMultiplier: Float = UserPreferences.DEFAULT_THRESHOLD_MULTIPLIER
    @Volatile var fcQuietPeriodMs: Long = UserPreferences.DEFAULT_FC_QUIET_PERIOD_S * 1000L
    @Volatile var minTransientsFc: Int = UserPreferences.DEFAULT_MIN_TRANSIENTS_FC
    @Volatile var minTransientsSc: Int = UserPreferences.DEFAULT_MIN_TRANSIENTS_SC
    @Volatile var minFcTimeMs: Long = UserPreferences.DEFAULT_MIN_FC_TIME_MIN * 60_000L

    // ---- Internal state ----
    private var phase = RoastPhase.IDLE
    private var sessionStartMs = 0L
    private var fcStartMs = 0L
    private var fcLastTransientMs = 0L
    private var transientWindowStartMs = 0L
    private var transientCountInWindow = 0
    @Volatile private var paused = false

    // Rolling record of FC-classified pops (timestamp + model FC confidence)
    // used by the sustained-roll detector. All access is on the IO thread.
    private data class FcPop(val t: Long, val fcProb: Float)
    private val fcPops = ArrayDeque<FcPop>()
    // FC probability of the most recent FC-classified frame (set in
    // classifyTransient, read when registering the pop). 1f when the model
    // isn't loaded and we fell back to timing-only.
    private var lastFcProb = 0f

    // ---- Output ----
    private val _phaseFlow = MutableStateFlow(RoastPhase.IDLE)
    val phaseFlow: StateFlow<RoastPhase> = _phaseFlow.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _eventFlow = MutableSharedFlow<CrackEvent>(extraBufferCapacity = 16)
    val eventFlow: SharedFlow<CrackEvent> = _eventFlow.asSharedFlow()

    private val _rmsFlow = MutableStateFlow(0f)
    val rmsFlow: StateFlow<Float> = _rmsFlow.asStateFlow()

    private val _ambientRmsFlow = MutableStateFlow(0f)
    val ambientRmsFlow: StateFlow<Float> = _ambientRmsFlow.asStateFlow()

    private val _crackCount = MutableStateFlow(0)
    val crackCount: StateFlow<Int> = _crackCount.asStateFlow()

    // Diagnostic: updated whenever the amplitude gate fires so the UI can show
    // live gate state without a laptop + logcat.
    // amplitudeRatio = rms/ambient of the last amplitude-gate-passing frame.
    // spectralRatio  = spectral ratio of that same frame (< CRACK_BAND_MIN_RATIO = failed).
    private val _diagAmplitudeRatio = MutableStateFlow(0f)
    val diagAmplitudeRatio: StateFlow<Float> = _diagAmplitudeRatio.asStateFlow()

    private val _diagSpectralRatio = MutableStateFlow(0f)
    val diagSpectralRatio: StateFlow<Float> = _diagSpectralRatio.asStateFlow()

    suspend fun loadPreferences() {
        thresholdMultiplier = prefs.thresholdMultiplier.first()
        fcQuietPeriodMs = prefs.fcQuietPeriodS.first() * 1000L
        minTransientsFc = prefs.minTransientsFc.first()
        minTransientsSc = prefs.minTransientsSc.first()
        minFcTimeMs = prefs.minFcTimeMin.first() * 60_000L
    }

    fun startSession() {
        if (!crackClassifier.isLoaded) crackClassifier.load()
        paused = false
        _isPaused.value = false
        _crackCount.value = 0
        _diagAmplitudeRatio.value = 0f
        _diagSpectralRatio.value = 0f
        synchronized(bufferLock) { frameBuffer.clear() }
        phase = RoastPhase.MONITORING
        sessionStartMs = System.currentTimeMillis()
        fcStartMs = 0L
        fcLastTransientMs = 0L
        transientWindowStartMs = sessionStartMs
        transientCountInWindow = 0
        fcPops.clear()
        lastFcProb = 0f
        detector.reset()
        _phaseFlow.value = phase
    }

    fun pauseSession() {
        if (phase != RoastPhase.IDLE) {
            paused = true
            _isPaused.value = true
        }
    }

    fun resumeSession() {
        if (paused) {
            paused = false
            _isPaused.value = false
            // Reset the transient window so the quiet pause period doesn't
            // trigger FC-complete via the quiet-period check.
            transientWindowStartMs = System.currentTimeMillis()
            fcLastTransientMs = 0L
            fcPops.clear()
        }
    }

    /**
     * The user manually marked first crack. Discard any (possibly false)
     * auto-detected FC state and re-anchor the state machine to FC-active at
     * *now*, so FC-end detection, the FC→SC floor, and the UI all reference the
     * real first crack — not an early startup misfire. Recovers from any phase
     * (a premature FC may have already advanced past MONITORING).
     */
    fun forceFirstCrack() {
        if (phase == RoastPhase.IDLE) return
        val now = System.currentTimeMillis()
        fcStartMs = now
        fcLastTransientMs = now
        fcPops.clear()
        resetTransientWindow(now)
        transitionTo(RoastPhase.FIRST_CRACK_ACTIVE)
    }

    fun startCooling() {
        if (phase == RoastPhase.SECOND_CRACK_ACTIVE || phase == RoastPhase.FIRST_CRACK_COMPLETE) {
            phase = RoastPhase.COOLING
            _phaseFlow.value = phase
        }
    }

    fun stopSession() {
        paused = false
        _isPaused.value = false
        phase = RoastPhase.IDLE
        _phaseFlow.value = phase
        detector.reset()
    }

    /** Called from the AudioRecord read loop on a background thread (IO dispatcher). */
    fun processBuffer(samples: ShortArray, count: Int = samples.size) {
        if (paused) return
        val now = System.currentTimeMillis()
        val rms = detector.computeRms(samples, count)
        recordFrame(now, rms)
        _rmsFlow.value = rms
        _ambientRmsFlow.value = detector.ambientRms

        when (phase) {
            RoastPhase.MONITORING -> {
                // Only FIRST-crack sounds count here. Confirmation is by
                // *sustained roll*, not the clock. The earliest-FC time is now a
                // confidence prior: before it we demand a much stronger roll
                // (mid-roast-start case + false-positive window); after it, the
                // normal roll suffices. See evaluateRoll().
                val elapsed = now - sessionStartMs
                if (elapsed < FC_STARTUP_GRACE_MS) {
                    // Machine spin-up — mute FC entirely (kills the ~13.6s
                    // startup false-crack). Pre-grace pops age out of the window.
                    detector.updateAmbient(rms)
                    pruneFcPops(now)
                } else if (classifyTransient(samples, count, rms, thresholdMultiplier, CrackType.FC)
                    == CrackType.FC) {
                    registerFcPop(now, lastFcProb)
                    val early = elapsed < minFcTimeMs
                    if (evaluateRoll(now, early)) {
                        confirmFirstCrack(now)
                    }
                } else {
                    detector.updateAmbient(rms)
                    pruneFcPops(now)
                }
            }

            RoastPhase.FIRST_CRACK_ACTIVE -> {
                if (classifyTransient(samples, count, rms, thresholdMultiplier, CrackType.FC)
                    == CrackType.FC) {
                    fcLastTransientMs = now
                    handleTransient(now, isFirstCrackPhase = true)
                } else {
                    detector.updateAmbient(rms)
                    // Check for quiet period → FC complete
                    if (fcLastTransientMs > 0 && now - fcLastTransientMs > fcQuietPeriodMs) {
                        val duration = now - fcStartMs
                        transitionTo(RoastPhase.FIRST_CRACK_COMPLETE)
                        _eventFlow.tryEmit(CrackEvent.FirstCrackEnded(duration))
                        resetTransientWindow(now)
                    }
                }
            }

            RoastPhase.FIRST_CRACK_COMPLETE -> {
                // SC is quieter than FC, so it needs a lower amplitude bar to
                // reach the model. Crucially, a continuing FC roll classifies as
                // FC here and does NOT count toward SC — that's the cascade fix.
                if (classifyTransient(samples, count, rms, scAmpMultiplier(), CrackType.SC)
                    == CrackType.SC) {
                    handleTransient(now, isFirstCrackPhase = false)
                } else {
                    detector.updateAmbient(rms)
                    resetWindowIfExpired(now, SC_WINDOW_MS)
                }
            }

            RoastPhase.SECOND_CRACK_ACTIVE,
            RoastPhase.COOLING,
            RoastPhase.IDLE -> { /* no-op */ }
        }
    }

    private enum class CrackType { NONE, FC, SC }

    private fun scAmpMultiplier(): Float = minOf(thresholdMultiplier, SC_AMP_MULTIPLIER)

    /**
     * Classifies a frame as FC, SC, or NONE. A frame must pass:
     *  1. Amplitude — louder than ambient × [multiplier] (after warmup)
     *  2. Spectrum  — energy in 2–9 kHz crack band ≥ CRACK_BAND_MIN_RATIO
     *  3. ML model  — 3-class softmax (ambient/FC/SC); argmax wins
     * If the model isn't loaded, a passing frame falls back to [fallback]
     * (preserves timing-only behaviour). Log tag "RC": adb logcat -s RC
     */
    private fun classifyTransient(
        samples: ShortArray, count: Int, rms: Float,
        multiplier: Float, fallback: CrackType
    ): CrackType {
        if (!detector.isTransient(rms, multiplier)) return CrackType.NONE

        val ampRatio  = rms / detector.ambientRms.coerceAtLeast(1f)
        val specRatio = spectralGate.crackBandRatio(samples, count)
        _diagAmplitudeRatio.value = ampRatio
        _diagSpectralRatio.value  = specRatio

        if (specRatio < SpectralGate.CRACK_BAND_MIN_RATIO) {
            Log.d(TAG, "AMP×${"%.1f".format(ampRatio)} spec=${"%.3f".format(specRatio)} spec-fail ✗")
            return CrackType.NONE
        }

        val probs = crackClassifier.classify(samples, count)
        if (probs == null) {
            lastFcProb = 1f   // no model → timing-only fallback, treat as confident
            Log.d(TAG, "AMP×${"%.1f".format(ampRatio)} spec=${"%.3f".format(specRatio)} model n/a → $fallback")
            return fallback
        }

        lastFcProb = probs[CrackClassifier.CLASS_FC]
        val type = when (indexOfMax(probs)) {
            CrackClassifier.CLASS_FC -> CrackType.FC
            CrackClassifier.CLASS_SC -> CrackType.SC
            else                     -> CrackType.NONE
        }
        Log.d(TAG, "AMP×${"%.1f".format(ampRatio)} spec=${"%.3f".format(specRatio)} " +
            "p[a/fc/sc]=${"%.2f".format(probs[0])}/${"%.2f".format(probs[1])}/${"%.2f".format(probs[2])} → $type")
        return type
    }

    private fun indexOfMax(a: FloatArray): Int {
        var idx = 0
        for (i in 1 until a.size) if (a[i] > a[idx]) idx = i
        return idx
    }

    // ---- FC sustained-roll detector (MONITORING) ------------------------------

    /** Record an FC-classified pop, bump the live crack counter, prune stale. */
    private fun registerFcPop(now: Long, fcProb: Float) {
        _crackCount.value++
        fcPops.addLast(FcPop(now, fcProb))
        pruneFcPops(now)
    }

    private fun pruneFcPops(now: Long) {
        val cutoff = now - FC_WINDOW_MS
        while (fcPops.isNotEmpty() && fcPops.first().t < cutoff) fcPops.removeFirst()
    }

    /**
     * True when the pops in the current window form a real first-crack roll:
     * enough pops (a higher bar when [early]) spread across >= FC_MIN_BUCKETS
     * distinct sub-windows. Before the earliest-FC time we also require decent
     * mean model confidence — that early window is where stray noise false-fires
     * (e.g. the 8-min CBR tick). A genuine roll clears even the early bar in
     * ~10-15s, which is what makes a mid-roast app start work.
     */
    private fun evaluateRoll(now: Long, early: Boolean): Boolean {
        pruneFcPops(now)
        if (fcPops.isEmpty()) return false

        val needPops = maxOf(
            minTransientsFc,
            if (early) FC_MIN_POPS_EARLY else FC_MIN_POPS_NORMAL
        )
        val pops = fcPops.size
        if (pops < needPops) return false

        val earliest = fcPops.first().t
        val buckets = fcPops.mapTo(HashSet()) { ((it.t - earliest) / FC_BUCKET_MS).toInt() }.size
        if (buckets < FC_MIN_BUCKETS) return false

        if (early) {
            val meanConf = fcPops.map { it.fcProb }.average().toFloat()
            if (meanConf < FC_EARLY_MIN_CONFIDENCE) {
                Log.d(TAG, "FC roll early-gate: pops=$pops buckets=$buckets " +
                    "meanConf=${"%.2f".format(meanConf)} < $FC_EARLY_MIN_CONFIDENCE — waiting")
                return false
            }
        }
        Log.d(TAG, "FC ROLL ok: pops=$pops (need $needPops) buckets=$buckets early=$early")
        return true
    }

    private fun confirmFirstCrack(now: Long) {
        fcStartMs = fcPops.first().t          // the roll began at the first pop
        fcLastTransientMs = now
        transitionTo(RoastPhase.FIRST_CRACK_ACTIVE)
        _eventFlow.tryEmit(CrackEvent.FirstCrackStarted)
        Log.d(TAG, "FC CONFIRMED after ${(now - sessionStartMs) / 1000}s (${fcPops.size} pops)")
        fcPops.clear()
        resetTransientWindow(now)
    }

    private fun handleTransient(now: Long, isFirstCrackPhase: Boolean) {
        _crackCount.value++
        val windowMs = if (isFirstCrackPhase) FC_WINDOW_MS else SC_WINDOW_MS
        if (now - transientWindowStartMs > windowMs) {
            // Window expired — this transient starts a fresh window
            transientWindowStartMs = now
            transientCountInWindow = 0
        }
        transientCountInWindow++

        val required = if (isFirstCrackPhase) minTransientsFc else minTransientsSc
        val spanMs = now - transientWindowStartMs
        Log.d(TAG, "TRANSIENT phase=$phase count=$transientCountInWindow/$required span=${spanMs}ms window=${windowMs}ms")

        // FC confirmation now lives in the sustained-roll detector
        // (registerFcPop/evaluateRoll/confirmFirstCrack). Here we only handle SC.
        if (!isFirstCrackPhase && phase == RoastPhase.FIRST_CRACK_COMPLETE &&
            transientCountInWindow >= required) {
            // Hard floor: SC physically can't follow FC this quickly.
            // Blocks the FC-roll-misread-as-SC cascade.
            val sinceFc = now - fcStartMs
            if (sinceFc < MIN_FC_TO_SC_MS) {
                Log.d(TAG, "SC blocked by FC→SC floor: ${sinceFc / 1000}s < ${MIN_FC_TO_SC_MS / 1000}s")
            } else {
                transitionTo(RoastPhase.SECOND_CRACK_ACTIVE)
                _eventFlow.tryEmit(CrackEvent.SecondCrackStarted)
                Log.d(TAG, "SC CONFIRMED after ${(now - sessionStartMs) / 1000}s")
                resetTransientWindow(now)
            }
        }
    }

    private fun transitionTo(newPhase: RoastPhase) {
        phase = newPhase
        _phaseFlow.value = newPhase
    }

    private fun resetWindowIfExpired(now: Long, windowMs: Long) {
        if (now - transientWindowStartMs > windowMs) {
            resetTransientWindow(now)
        }
    }

    private fun resetTransientWindow(now: Long) {
        transientWindowStartMs = now
        transientCountInWindow = 0
    }
}
