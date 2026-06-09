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

        // Rolling transient count window (3 seconds)
        const val TRANSIENT_WINDOW_MS = 3_000L
    }

    // ---- Settings (updated from prefs before each session) ----
    @Volatile var thresholdMultiplier: Float = UserPreferences.DEFAULT_THRESHOLD_MULTIPLIER
    @Volatile var fcQuietPeriodMs: Long = UserPreferences.DEFAULT_FC_QUIET_PERIOD_S * 1000L
    @Volatile var minTransientsFc: Int = UserPreferences.DEFAULT_MIN_TRANSIENTS_FC
    @Volatile var minTransientsSc: Int = UserPreferences.DEFAULT_MIN_TRANSIENTS_SC

    // ---- Internal state ----
    private var phase = RoastPhase.IDLE
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
    }

    fun startSession() {
        phase = RoastPhase.MONITORING
        fcStartMs = 0L
        fcLastTransientMs = 0L
        transientWindowStartMs = System.currentTimeMillis()
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
                val isSpike = detector.isTransient(rms, thresholdMultiplier)
                if (isSpike) {
                    handleTransient(now, isFirstCrackPhase = true)
                } else {
                    detector.updateAmbient(rms)
                    resetWindowIfExpired(now)
                }
            }

            RoastPhase.FIRST_CRACK_ACTIVE -> {
                val isSpike = detector.isTransient(rms, thresholdMultiplier)
                if (isSpike) {
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
                val isSpike = detector.isTransient(rms, thresholdMultiplier)
                if (isSpike) {
                    handleTransient(now, isFirstCrackPhase = false)
                } else {
                    detector.updateAmbient(rms)
                    resetWindowIfExpired(now)
                }
            }

            RoastPhase.SECOND_CRACK_ACTIVE,
            RoastPhase.COOLING,
            RoastPhase.IDLE -> { /* no-op */ }
        }
    }

    private fun handleTransient(now: Long, isFirstCrackPhase: Boolean) {
        if (now - transientWindowStartMs > TRANSIENT_WINDOW_MS) {
            // Window expired — start fresh
            transientWindowStartMs = now
            transientCountInWindow = 0
        }
        transientCountInWindow++

        val required = if (isFirstCrackPhase) minTransientsFc else minTransientsSc
        if (transientCountInWindow >= required) {
            when {
                isFirstCrackPhase && phase == RoastPhase.MONITORING -> {
                    fcStartMs = transientWindowStartMs
                    fcLastTransientMs = now
                    transitionTo(RoastPhase.FIRST_CRACK_ACTIVE)
                    _eventFlow.tryEmit(CrackEvent.FirstCrackStarted)
                }
                !isFirstCrackPhase && phase == RoastPhase.FIRST_CRACK_COMPLETE -> {
                    transitionTo(RoastPhase.SECOND_CRACK_ACTIVE)
                    _eventFlow.tryEmit(CrackEvent.SecondCrackStarted)
                }
            }
            resetTransientWindow(now)
        }
    }

    private fun transitionTo(newPhase: RoastPhase) {
        phase = newPhase
        _phaseFlow.value = newPhase
    }

    private fun resetWindowIfExpired(now: Long) {
        if (now - transientWindowStartMs > TRANSIENT_WINDOW_MS) {
            resetTransientWindow(now)
        }
    }

    private fun resetTransientWindow(now: Long) {
        transientWindowStartMs = now
        transientCountInWindow = 0
    }
}
