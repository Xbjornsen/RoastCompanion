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

        // First crack is a rolling series of pops, not a burst — collect
        // candidates over a wide window and require them to be spread out.
        const val FC_WINDOW_MS = 15_000L
        // First→last crack in the window must span at least this long.
        // Rejects "3 mouth clicks in 2 seconds".
        const val FC_MIN_SPAN_MS = 4_000L

        // Second crack window (FC context already gates this phase)
        const val SC_WINDOW_MS = 10_000L

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
        }
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
                // Only FIRST-crack sounds count here. The time gate still
                // applies — FC can't physically happen early in a CBR-101 roast.
                if (classifyTransient(samples, count, rms, thresholdMultiplier, CrackType.FC)
                    == CrackType.FC) {
                    val elapsed = now - sessionStartMs
                    if (elapsed >= minFcTimeMs) {
                        handleTransient(now, isFirstCrackPhase = true)
                    } else {
                        Log.d(TAG, "FC blocked by time gate: ${elapsed / 1000}s < ${minFcTimeMs / 1000}s")
                    }
                } else {
                    detector.updateAmbient(rms)
                    resetWindowIfExpired(now, FC_WINDOW_MS)
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
            Log.d(TAG, "AMP×${"%.1f".format(ampRatio)} spec=${"%.3f".format(specRatio)} model n/a → $fallback")
            return fallback
        }

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

        if (transientCountInWindow >= required) {
            when {
                isFirstCrackPhase && phase == RoastPhase.MONITORING -> {
                    // Pattern requirement: cracks must be SPREAD over time.
                    // A real first crack rolls for 30s+; a burst of clicks
                    // in a couple of seconds doesn't qualify — keep waiting
                    // for more evidence inside the window.
                    if (spanMs >= FC_MIN_SPAN_MS) {
                        fcStartMs = transientWindowStartMs
                        fcLastTransientMs = now
                        transitionTo(RoastPhase.FIRST_CRACK_ACTIVE)
                        _eventFlow.tryEmit(CrackEvent.FirstCrackStarted)
                        Log.d(TAG, "FC CONFIRMED after ${(now - sessionStartMs) / 1000}s")
                        resetTransientWindow(now)
                    } else {
                        Log.d(TAG, "FC count=$transientCountInWindow/$required but span too short: ${spanMs}ms < ${FC_MIN_SPAN_MS}ms — waiting")
                    }
                }
                !isFirstCrackPhase && phase == RoastPhase.FIRST_CRACK_COMPLETE -> {
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
