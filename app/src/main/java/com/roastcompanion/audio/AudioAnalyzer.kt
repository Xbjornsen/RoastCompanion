package com.roastcompanion.audio

import android.media.AudioFormat
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
    private val prefs: UserPreferences
) {
    companion object {
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
    }

    private val spectralGate = SpectralGate()

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

    // ---- Output ----
    private val _phaseFlow = MutableStateFlow(RoastPhase.IDLE)
    val phaseFlow: StateFlow<RoastPhase> = _phaseFlow.asStateFlow()

    private val _eventFlow = MutableSharedFlow<CrackEvent>(extraBufferCapacity = 16)
    val eventFlow: SharedFlow<CrackEvent> = _eventFlow.asSharedFlow()

    private val _rmsFlow = MutableStateFlow(0f)
    val rmsFlow: StateFlow<Float> = _rmsFlow.asStateFlow()

    private val _ambientRmsFlow = MutableStateFlow(0f)
    val ambientRmsFlow: StateFlow<Float> = _ambientRmsFlow.asStateFlow()

    suspend fun loadPreferences() {
        thresholdMultiplier = prefs.thresholdMultiplier.first()
        fcQuietPeriodMs = prefs.fcQuietPeriodS.first() * 1000L
        minTransientsFc = prefs.minTransientsFc.first()
        minTransientsSc = prefs.minTransientsSc.first()
        minFcTimeMs = prefs.minFcTimeMin.first() * 60_000L
    }

    fun startSession() {
        phase = RoastPhase.MONITORING
        sessionStartMs = System.currentTimeMillis()
        fcStartMs = 0L
        fcLastTransientMs = 0L
        transientWindowStartMs = sessionStartMs
        transientCountInWindow = 0
        detector.reset()
        _phaseFlow.value = phase
    }

    fun startCooling() {
        if (phase == RoastPhase.SECOND_CRACK_ACTIVE || phase == RoastPhase.FIRST_CRACK_COMPLETE) {
            phase = RoastPhase.COOLING
            _phaseFlow.value = phase
        }
    }

    fun stopSession() {
        phase = RoastPhase.IDLE
        _phaseFlow.value = phase
        detector.reset()
    }

    /** Called from the AudioRecord read loop on a background thread (IO dispatcher). */
    fun processBuffer(samples: ShortArray, count: Int = samples.size) {
        val now = System.currentTimeMillis()
        val rms = detector.computeRms(samples, count)
        _rmsFlow.value = rms
        _ambientRmsFlow.value = detector.ambientRms

        when (phase) {
            RoastPhase.MONITORING -> {
                if (isCrackTransient(samples, count, rms)) {
                    // Time gate: first crack physically cannot happen this
                    // early in a CBR-101 roast. Ignore the spike entirely —
                    // and don't let it inflate the ambient estimate either.
                    if (now - sessionStartMs >= minFcTimeMs) {
                        handleTransient(now, isFirstCrackPhase = true)
                    }
                } else {
                    detector.updateAmbient(rms)
                    resetWindowIfExpired(now, FC_WINDOW_MS)
                }
            }

            RoastPhase.FIRST_CRACK_ACTIVE -> {
                if (isCrackTransient(samples, count, rms)) {
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
                if (isCrackTransient(samples, count, rms)) {
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

    /**
     * A frame counts as a crack only if it passes BOTH gates:
     *  1. Amplitude — louder than ambient × multiplier (after warmup)
     *  2. Spectrum — energy concentrated in the 2–9 kHz crack band,
     *     which rejects voices, thuds, and low-frequency noise that
     *     happen to be loud.
     */
    private fun isCrackTransient(samples: ShortArray, count: Int, rms: Float): Boolean {
        if (!detector.isTransient(rms, thresholdMultiplier)) return false
        return spectralGate.isCrackLike(samples, count)
    }

    private fun handleTransient(now: Long, isFirstCrackPhase: Boolean) {
        val windowMs = if (isFirstCrackPhase) FC_WINDOW_MS else SC_WINDOW_MS
        if (now - transientWindowStartMs > windowMs) {
            // Window expired — this transient starts a fresh window
            transientWindowStartMs = now
            transientCountInWindow = 0
        }
        transientCountInWindow++

        val required = if (isFirstCrackPhase) minTransientsFc else minTransientsSc
        if (transientCountInWindow >= required) {
            when {
                isFirstCrackPhase && phase == RoastPhase.MONITORING -> {
                    // Pattern requirement: cracks must be SPREAD over time.
                    // A real first crack rolls for 30s+; a burst of clicks
                    // in a couple of seconds doesn't qualify — keep waiting
                    // for more evidence inside the window.
                    if (now - transientWindowStartMs >= FC_MIN_SPAN_MS) {
                        fcStartMs = transientWindowStartMs
                        fcLastTransientMs = now
                        transitionTo(RoastPhase.FIRST_CRACK_ACTIVE)
                        _eventFlow.tryEmit(CrackEvent.FirstCrackStarted)
                        resetTransientWindow(now)
                    }
                }
                !isFirstCrackPhase && phase == RoastPhase.FIRST_CRACK_COMPLETE -> {
                    transitionTo(RoastPhase.SECOND_CRACK_ACTIVE)
                    _eventFlow.tryEmit(CrackEvent.SecondCrackStarted)
                    resetTransientWindow(now)
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
