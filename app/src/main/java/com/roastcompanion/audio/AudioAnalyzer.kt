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

/**
 * Turns the mic stream into roast phases + crack events.
 *
 * Detection (v2): every 50 ms frame's *impulsiveness* (FeatureExtractor) feeds
 * RollDetector, which fires first/second crack when the rate of 1 ms "pops"
 * rises well above the roast's own recent baseline. Validated offline against
 * by-ear labels with training_data/scripts/harness.py — see RollDetector.
 *
 * The v1 path (amplitude gate + spectral gate + TFLite classifier + pop-count
 * roll) is gone: the classifier never loaded on device (heap ByteBuffer), and
 * its features didn't match training (int16 vs float scaling, filterbank).
 * TransientDetector/SpectralGate remain only for the live level + diagnostics
 * shown on the Roast screen and stored with manual confirmations.
 */
@Singleton
class AudioAnalyzer @Inject constructor(
    private val detector: TransientDetector,
    private val prefs: UserPreferences,
) {
    companion object {
        private const val TAG = "RC"
        const val SAMPLE_RATE = 44100
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        // 50ms window → 20 frames/second (FeatureExtractor.FRAME)
        const val WINDOW_MS = 50
        val SAMPLES_PER_WINDOW = SAMPLE_RATE * WINDOW_MS / 1000  // 2205

        /** Auto-FC may fire at most this long before the "Earliest First Crack" setting. */
        const val EARLIEST_FC_SLACK_S = 60
    }

    private val spectralGate = SpectralGate()

    // ---- Frame look-back buffer (last 10 seconds at 20fps = 200 entries) ----
    private data class FrameSnapshot(val timestampMs: Long, val rmsRatio: Float)
    private val frameBuffer = ArrayDeque<FrameSnapshot>(200)
    private val bufferLock = Any()

    private fun recordFrame(now: Long, rms: Float) {
        val ambient = detector.ambientRms.coerceAtLeast(1f)
        synchronized(bufferLock) {
            if (frameBuffer.size >= 200) frameBuffer.removeFirst()
            frameBuffer.addLast(FrameSnapshot(now, rms / ambient))
        }
    }

    /** Highest rms/ambient ratio in the last [windowMs] (stored with manual confirmations). */
    fun peakRmsRatioInLastSeconds(windowMs: Long = 5_000L): Float {
        val cutoff = System.currentTimeMillis() - windowMs
        return synchronized(bufferLock) {
            frameBuffer.filter { it.timestampMs >= cutoff }.maxOfOrNull { it.rmsRatio } ?: 0f
        }
    }

    // ---- Settings (loaded before each session) ----
    @Volatile var thresholdMultiplier: Float = UserPreferences.DEFAULT_THRESHOLD_MULTIPLIER  // diagnostics only
    @Volatile var fcQuietPeriodMs: Long = UserPreferences.DEFAULT_FC_QUIET_PERIOD_S * 1000L
    @Volatile var sensitivity: Int = RollDetector.DEFAULT_SENSITIVITY
    @Volatile var minFcTimeMs: Long = UserPreferences.DEFAULT_MIN_FC_TIME_MIN * 60_000L

    // ---- Internal state (IO thread) ----
    @Volatile private var phase = RoastPhase.IDLE
    private var roll = RollDetector()
    private val rollLock = Any()   // push() runs on IO, forceFirstCrack() on main
    private var fcStartMs = 0L
    private var popsAtFc = 0L
    @Volatile private var paused = false

    /**
     * Wall-clock time the recorded audio (and the training WAV) starts at, i.e.
     * when the first frame began. Training labels must be measured from this, not
     * from the session start (the recorder starts later) or the pause-adjusted timer
     * (the WAV keeps recording while paused). 0 until the first frame arrives.
     */
    @Volatile var audioStartWallMs = 0L
        private set

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

    /** Pops counted since first crack started (0 before FC). */
    private val _crackCount = MutableStateFlow(0)
    val crackCount: StateFlow<Int> = _crackCount.asStateFlow()

    // Diagnostics for the Roast screen: last loud frame's rms/ambient and crack-band ratio.
    private val _diagAmplitudeRatio = MutableStateFlow(0f)
    val diagAmplitudeRatio: StateFlow<Float> = _diagAmplitudeRatio.asStateFlow()

    private val _diagSpectralRatio = MutableStateFlow(0f)
    val diagSpectralRatio: StateFlow<Float> = _diagSpectralRatio.asStateFlow()

    suspend fun loadPreferences() {
        thresholdMultiplier = prefs.thresholdMultiplier.first()
        fcQuietPeriodMs = prefs.fcQuietPeriodS.first() * 1000L
        minFcTimeMs = prefs.minFcTimeMin.first() * 60_000L
        sensitivity = prefs.crackSensitivity.first()
    }

    fun startSession() {
        paused = false
        _isPaused.value = false
        _crackCount.value = 0
        _diagAmplitudeRatio.value = 0f
        _diagSpectralRatio.value = 0f
        synchronized(bufferLock) { frameBuffer.clear() }
        synchronized(rollLock) { roll = RollDetector(
            minFcSeconds = maxOf(0, (minFcTimeMs / 1000L).toInt() - EARLIEST_FC_SLACK_S),
            fcEndQuietS = (fcQuietPeriodMs / 1000L).toInt().coerceAtLeast(5),
            sensitivity = sensitivity,
        ) }
        fcStartMs = 0L
        popsAtFc = 0L
        audioStartWallMs = 0L
        detector.reset()
        transitionTo(RoastPhase.MONITORING)
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
        }
    }

    /**
     * The owner tapped "FC start": FC is NOW. Re-anchors the detector (second-crack
     * timing is measured from here) and discards any earlier auto FC/FC-end/SC.
     */
    fun forceFirstCrack() {
        if (phase == RoastPhase.IDLE) return
        synchronized(rollLock) {
            roll.forceFirstCrack()
            popsAtFc = roll.totalPops
        }
        fcStartMs = System.currentTimeMillis()
        _crackCount.value = 0
        transitionTo(RoastPhase.FIRST_CRACK_ACTIVE)
    }

    fun startCooling() {
        if (phase == RoastPhase.SECOND_CRACK_ACTIVE || phase == RoastPhase.FIRST_CRACK_COMPLETE ||
            phase == RoastPhase.FIRST_CRACK_ACTIVE) {
            transitionTo(RoastPhase.COOLING)
        }
    }

    fun stopSession() {
        paused = false
        _isPaused.value = false
        transitionTo(RoastPhase.IDLE)
        detector.reset()
    }

    /** Called from the AudioRecord read loop on a background thread (IO dispatcher). */
    fun processBuffer(samples: ShortArray, count: Int = samples.size) {
        val now = System.currentTimeMillis()
        if (audioStartWallMs == 0L && phase != RoastPhase.IDLE) audioStartWallMs = now - WINDOW_MS
        if (paused) return

        // Live level + diagnostics (not used for detection)
        val rms = detector.computeRms(samples, count)
        recordFrame(now, rms)
        _rmsFlow.value = rms
        _ambientRmsFlow.value = detector.ambientRms
        if (detector.isTransient(rms, thresholdMultiplier)) {
            _diagAmplitudeRatio.value = rms / detector.ambientRms.coerceAtLeast(1f)
            _diagSpectralRatio.value = spectralGate.crackBandRatio(samples, count)
        } else {
            detector.updateAmbient(rms)
        }

        when (phase) {
            RoastPhase.IDLE, RoastPhase.COOLING, RoastPhase.SECOND_CRACK_ACTIVE -> return
            else -> Unit
        }

        val imp = FeatureExtractor.impulsiveness(samples, count)
        val ev: RollDetector.Event?
        val fedMs: Long
        synchronized(rollLock) {
            ev = roll.push(imp)
            fedMs = roll.fedMs
            if (phase != RoastPhase.MONITORING) _crackCount.value = (roll.totalPops - popsAtFc).toInt()
            if (ev == RollDetector.Event.FIRST_CRACK && phase == RoastPhase.MONITORING) popsAtFc = roll.totalPops
        }

        when (ev) {
            RollDetector.Event.FIRST_CRACK -> if (phase == RoastPhase.MONITORING) {
                fcStartMs = now
                transitionTo(RoastPhase.FIRST_CRACK_ACTIVE)
                _eventFlow.tryEmit(CrackEvent.FirstCrackStarted)
                Log.d(TAG, "FC detected at ${fedMs / 1000}s of audio")
            }
            RollDetector.Event.FIRST_CRACK_END -> if (phase == RoastPhase.FIRST_CRACK_ACTIVE) {
                transitionTo(RoastPhase.FIRST_CRACK_COMPLETE)
                _eventFlow.tryEmit(CrackEvent.FirstCrackEnded(now - fcStartMs))
            }
            RollDetector.Event.SECOND_CRACK -> {
                transitionTo(RoastPhase.SECOND_CRACK_ACTIVE)
                _eventFlow.tryEmit(CrackEvent.SecondCrackStarted)
                Log.d(TAG, "SC detected at ${fedMs / 1000}s of audio")
            }
            null -> Unit
        }
    }

    private fun transitionTo(newPhase: RoastPhase) {
        phase = newPhase
        _phaseFlow.value = newPhase
    }
}
