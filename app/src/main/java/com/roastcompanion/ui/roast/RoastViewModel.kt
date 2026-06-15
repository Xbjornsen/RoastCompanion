package com.roastcompanion.ui.roast

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roastcompanion.audio.AudioAnalyzer
import com.roastcompanion.audio.CrackEvent
import com.roastcompanion.audio.RoastPhase
import com.roastcompanion.data.db.entity.CrackConfirmation
import com.roastcompanion.data.db.entity.RoastSession
import com.roastcompanion.data.preferences.UserPreferences
import com.roastcompanion.data.repository.RoastRepository
import com.roastcompanion.model.CarryoverState
import com.roastcompanion.model.CookingCarryover
import com.roastcompanion.service.RoastMonitorService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

sealed class RoastAlert {
    object FirstCrackDetected : RoastAlert()
    object FirstCrackComplete : RoastAlert()
    object SecondCrackDetected : RoastAlert()
}

@HiltViewModel
class RoastViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val audioAnalyzer: AudioAnalyzer,
    private val repository: RoastRepository,
    private val prefs: UserPreferences
) : ViewModel() {

    // Expose analyzer flows directly — no service binding needed
    val phase: StateFlow<RoastPhase>   = audioAnalyzer.phaseFlow
    val isPaused: StateFlow<Boolean>   = audioAnalyzer.isPaused
    val rmsLevel: StateFlow<Float>     = audioAnalyzer.rmsFlow
    val ambientLevel: StateFlow<Float> = audioAnalyzer.ambientRmsFlow
    val crackCount: StateFlow<Int>      = audioAnalyzer.crackCount
    val diagAmpRatio: StateFlow<Float>  = audioAnalyzer.diagAmplitudeRatio
    val diagSpecRatio: StateFlow<Float> = audioAnalyzer.diagSpectralRatio

    val keepScreenOn: StateFlow<Boolean> = prefs.keepScreenOn
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserPreferences.DEFAULT_KEEP_SCREEN_ON)

    val recordForTraining: StateFlow<Boolean> = prefs.recordForTraining
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserPreferences.DEFAULT_RECORD_FOR_TRAINING)

    /** Latest favourited roast — live reference targets during a roast. */
    val referenceRoast: StateFlow<RoastSession?> = repository.getLatestFavorite()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _sessionTimerMs = MutableStateFlow(0L)
    val sessionTimerMs: StateFlow<Long> = _sessionTimerMs.asStateFlow()

    private val _carryoverState = MutableStateFlow<CarryoverState?>(null)
    val carryoverState: StateFlow<CarryoverState?> = _carryoverState.asStateFlow()

    private val _alerts = MutableSharedFlow<RoastAlert>(extraBufferCapacity = 8)
    val alerts: SharedFlow<RoastAlert> = _alerts.asSharedFlow()

    private val _fcStartMs = MutableStateFlow<Long?>(null)
    val fcStartMs: StateFlow<Long?> = _fcStartMs.asStateFlow()

    private val _fcEndMs = MutableStateFlow<Long?>(null)
    val fcEndMs: StateFlow<Long?> = _fcEndMs.asStateFlow()

    private val _scDetectedMs = MutableStateFlow<Long?>(null)
    val scDetectedMs: StateFlow<Long?> = _scDetectedMs.asStateFlow()

    // Which crack types the user has manually confirmed this session
    private val _confirmedTypes = MutableStateFlow<Set<String>>(emptySet())
    val confirmedTypes: StateFlow<Set<String>> = _confirmedTypes.asStateFlow()

    private var currentSessionId = -1L
    private var sessionStartMs = 0L
    private var pausedAtMs = 0L
    private var timerJob: Job? = null
    private var carryoverJob: Job? = null
    private var isSessionActive = false

    init {
        viewModelScope.launch {
            audioAnalyzer.eventFlow.collect { event -> handleCrackEvent(event) }
        }
    }

    private fun handleCrackEvent(event: CrackEvent) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            when (event) {
                is CrackEvent.FirstCrackStarted -> {
                    _fcStartMs.value = now
                    if (currentSessionId >= 0) repository.updateFirstCrackStart(currentSessionId, now)
                    _alerts.emit(RoastAlert.FirstCrackDetected)
                }
                is CrackEvent.FirstCrackEnded -> {
                    _fcEndMs.value = now
                    if (currentSessionId >= 0) repository.updateFirstCrackEnd(currentSessionId, now, event.durationMs)
                    _alerts.emit(RoastAlert.FirstCrackComplete)
                }
                is CrackEvent.SecondCrackStarted -> {
                    _scDetectedMs.value = now
                    if (currentSessionId >= 0) repository.updateSecondCrack(currentSessionId, now)
                    _alerts.emit(RoastAlert.SecondCrackDetected)
                }
            }
        }
    }

    fun onStartRoast(context: Context) {
        if (isSessionActive) return
        isSessionActive = true
        val startMs = System.currentTimeMillis()
        sessionStartMs = startMs
        // Clear UI immediately so stale values don't flash
        _sessionTimerMs.value = 0L
        _fcStartMs.value = null
        _fcEndMs.value = null
        _scDetectedMs.value = null
        _carryoverState.value = null
        _confirmedTypes.value = emptySet()

        viewModelScope.launch {
            val doRecord = prefs.recordForTraining.first()
            currentSessionId = repository.createSession(startMs)
            startTimer()
            context.startForegroundService(
                RoastMonitorService.startIntent(context, startMs, doRecord)
            )
        }
    }

    fun onStopRoast(context: Context) {
        if (!isSessionActive) return
        isSessionActive = false
        timerJob?.cancel()
        carryoverJob?.cancel()

        val sid        = currentSessionId
        val startMs    = sessionStartMs
        val fcStartMs  = _fcStartMs.value
        val fcEndMs    = _fcEndMs.value
        val scStartMs  = _scDetectedMs.value
        val doRecord   = recordForTraining.value

        viewModelScope.launch {
            if (sid >= 0) {
                repository.endSession(sid, System.currentTimeMillis(), startMs)
                if (doRecord) writeTrainingJson(sid, startMs, fcStartMs, fcEndMs, scStartMs)
            }
        }

        context.startService(RoastMonitorService.stopIntent(context))
        currentSessionId = -1L
    }

    private suspend fun writeTrainingJson(
        sessionId: Long,
        startMs: Long,
        fcStartMs: Long?,
        fcEndMs: Long?,
        scStartMs: Long?
    ) {
        try {
            val confirmations = repository.getConfirmationsForSession(sessionId)
            val json = buildTrainingJson(sessionId, startMs, fcStartMs, fcEndMs, scStartMs, confirmations)
            withContext(Dispatchers.IO) {
                val dir = appContext.getExternalFilesDir("training")
                    ?: appContext.filesDir.resolve("training")
                dir.mkdirs()
                File(dir, "training_$startMs.json").writeText(json, Charsets.UTF_8)
            }
        } catch (e: Exception) {
            android.util.Log.e("RC", "Failed to write training JSON: ${e.message}")
        }
    }

    private fun buildTrainingJson(
        sessionId: Long,
        startMs: Long,
        fcStartMs: Long?,
        fcEndMs: Long?,
        scStartMs: Long?,
        confirmations: List<CrackConfirmation>
    ): String {
        val confirmedJson = confirmations.joinToString(",\n    ") { c ->
            """{"type":"${c.crackType}","elapsedMs":${c.elapsedMs},"peakRmsRatio":${"%.3f".format(c.rmsRatio)},"spectralRatio":${"%.3f".format(c.spectralRatio)}}"""
        }

        val autoFc  = fcStartMs?.let { it - startMs }
        val autoFcE = fcEndMs?.let   { it - startMs }
        val autoSc  = scStartMs?.let { it - startMs }

        return """{
  "version": 1,
  "sessionId": $sessionId,
  "startTimeMs": $startMs,
  "audio": {
    "filename": "training_$startMs.wav",
    "sampleRate": ${AudioAnalyzer.SAMPLE_RATE},
    "channels": 1,
    "bitsPerSample": 16
  },
  "confirmed": [
    $confirmedJson
  ],
  "autoDetected": {
    "fcStartElapsedMs": ${autoFc ?: "null"},
    "fcEndElapsedMs": ${autoFcE ?: "null"},
    "scStartElapsedMs": ${autoSc ?: "null"}
  }
}"""
    }

    fun onStartCooling(context: Context) {
        audioAnalyzer.startCooling()
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            if (currentSessionId >= 0) repository.updateCoolingStart(currentSessionId, now)
            val totalS = prefs.carryoverDurationS.first()
            startCarryoverCountdown(totalS)
        }
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            val start = sessionStartMs
            while (true) {
                _sessionTimerMs.value = System.currentTimeMillis() - start
                delay(500)
            }
        }
    }

    private fun startCarryoverCountdown(totalS: Int) {
        carryoverJob?.cancel()
        carryoverJob = viewModelScope.launch {
            var elapsed = 0
            while (elapsed <= totalS) {
                _carryoverState.value = CookingCarryover.buildState(elapsed, totalS)
                if (elapsed >= totalS) break
                delay(1_000)
                elapsed++
            }
        }
    }

    fun onPauseRoast() {
        if (!isSessionActive || audioAnalyzer.isPaused.value) return
        pausedAtMs = _sessionTimerMs.value
        timerJob?.cancel()
        audioAnalyzer.pauseSession()
    }

    fun onResumeRoast() {
        if (!isSessionActive || !audioAnalyzer.isPaused.value) return
        audioAnalyzer.resumeSession()
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            val resumeWallMs = System.currentTimeMillis()
            val offset = pausedAtMs
            while (true) {
                _sessionTimerMs.value = offset + (System.currentTimeMillis() - resumeWallMs)
                delay(500)
            }
        }
    }

    fun onResetRoast(context: Context) {
        timerJob?.cancel()
        carryoverJob?.cancel()
        _sessionTimerMs.value = 0L
        _fcStartMs.value = null
        _fcEndMs.value = null
        _scDetectedMs.value = null

        if (isSessionActive) {
            viewModelScope.launch {
                if (currentSessionId >= 0) repository.deleteSession(currentSessionId)
            }
            context.startService(RoastMonitorService.stopIntent(context))
        }

        isSessionActive = false
        currentSessionId = -1L
        _confirmedTypes.value = emptySet()
        audioAnalyzer.stopSession()
    }

    /**
     * Called when the user taps a confirm chip on the Roast screen.
     * Captures peak audio features from the look-back buffer and persists them
     * alongside the auto-detected timestamp (null if the app missed it).
     * If the app missed this event entirely, the confirmed time is also written
     * back to the session so the log isn't empty.
     */
    fun confirmCrack(crackType: String) {
        val sessionId = currentSessionId
        val confirmedMs = System.currentTimeMillis()
        val elapsedMs = _sessionTimerMs.value
        val rmsRatio = audioAnalyzer.peakRmsRatioInLastSeconds()
        val spectralRatio = audioAnalyzer.diagSpectralRatio.value

        val autoDetectedMs: Long? = when (crackType) {
            "FC_START" -> _fcStartMs.value
            "FC_END"   -> _fcEndMs.value
            "SC_START" -> _scDetectedMs.value
            else       -> null
        }

        viewModelScope.launch {
            if (sessionId >= 0) {
                repository.confirmCrack(
                    sessionId, crackType, confirmedMs, elapsedMs,
                    autoDetectedMs, rmsRatio, spectralRatio
                )
                // Back-fill the session record if the app missed this event
                when (crackType) {
                    "FC_START" -> if (_fcStartMs.value == null) {
                        _fcStartMs.value = confirmedMs
                        repository.updateFirstCrackStart(sessionId, confirmedMs)
                    }
                    "FC_END" -> if (_fcEndMs.value == null) {
                        _fcEndMs.value = confirmedMs
                        val dur = confirmedMs - (_fcStartMs.value ?: confirmedMs)
                        repository.updateFirstCrackEnd(sessionId, confirmedMs, dur)
                    }
                    "SC_START" -> if (_scDetectedMs.value == null) {
                        _scDetectedMs.value = confirmedMs
                        repository.updateSecondCrack(sessionId, confirmedMs)
                    }
                }
            }
        }

        _confirmedTypes.value = _confirmedTypes.value + crackType
    }

    fun isSessionActive(): Boolean = isSessionActive

    fun getCurrentSessionId(): Long = currentSessionId
}
