package com.roastcompanion.ui.roast

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roastcompanion.audio.AudioAnalyzer
import com.roastcompanion.audio.CrackEvent
import com.roastcompanion.audio.RoastPhase
import com.roastcompanion.data.db.entity.RoastSession
import com.roastcompanion.data.preferences.UserPreferences
import com.roastcompanion.data.repository.RoastRepository
import com.roastcompanion.model.CarryoverState
import com.roastcompanion.model.CookingCarryover
import com.roastcompanion.service.RoastMonitorService
import dagger.hilt.android.lifecycle.HiltViewModel
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
import javax.inject.Inject

sealed class RoastAlert {
    object FirstCrackDetected : RoastAlert()
    object FirstCrackComplete : RoastAlert()
    object SecondCrackDetected : RoastAlert()
}

@HiltViewModel
class RoastViewModel @Inject constructor(
    // AudioAnalyzer is @Singleton — same instance that the Service uses
    private val audioAnalyzer: AudioAnalyzer,
    private val repository: RoastRepository,
    private val prefs: UserPreferences
) : ViewModel() {

    // Expose analyzer flows directly — no service binding needed
    val phase: StateFlow<RoastPhase>   = audioAnalyzer.phaseFlow
    val isPaused: StateFlow<Boolean>   = audioAnalyzer.isPaused
    val rmsLevel: StateFlow<Float>     = audioAnalyzer.rmsFlow
    val ambientLevel: StateFlow<Float> = audioAnalyzer.ambientRmsFlow
    val crackCount: StateFlow<Int>     = audioAnalyzer.crackCount

    val keepScreenOn: StateFlow<Boolean> = prefs.keepScreenOn
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserPreferences.DEFAULT_KEEP_SCREEN_ON)

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
        // Clear UI immediately so stale values don't flash
        _sessionTimerMs.value = 0L
        _fcStartMs.value = null
        _fcEndMs.value = null
        _scDetectedMs.value = null
        _carryoverState.value = null

        viewModelScope.launch {
            sessionStartMs = System.currentTimeMillis()
            currentSessionId = repository.createSession(sessionStartMs)
            startTimer()
        }

        context.startForegroundService(RoastMonitorService.startIntent(context))
    }

    fun onStopRoast(context: Context) {
        if (!isSessionActive) return
        isSessionActive = false
        timerJob?.cancel()
        carryoverJob?.cancel()

        viewModelScope.launch {
            if (currentSessionId >= 0) {
                repository.endSession(currentSessionId, System.currentTimeMillis(), sessionStartMs)
            }
        }

        context.startService(RoastMonitorService.stopIntent(context))
        currentSessionId = -1L
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
        audioAnalyzer.stopSession()
    }

    fun isSessionActive(): Boolean = isSessionActive

    fun getCurrentSessionId(): Long = currentSessionId
}
