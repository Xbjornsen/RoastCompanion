package com.roastcompanion.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roastcompanion.data.preferences.UserPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: UserPreferences
) : ViewModel() {

    val thresholdMultiplier: StateFlow<Float> = prefs.thresholdMultiplier
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserPreferences.DEFAULT_THRESHOLD_MULTIPLIER)

    val fcQuietPeriodS: StateFlow<Int> = prefs.fcQuietPeriodS
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserPreferences.DEFAULT_FC_QUIET_PERIOD_S)

    val carryoverDurationS: StateFlow<Int> = prefs.carryoverDurationS
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserPreferences.DEFAULT_CARRYOVER_DURATION_S)

    val minTransientsFc: StateFlow<Int> = prefs.minTransientsFc
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserPreferences.DEFAULT_MIN_TRANSIENTS_FC)

    val minTransientsSc: StateFlow<Int> = prefs.minTransientsSc
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserPreferences.DEFAULT_MIN_TRANSIENTS_SC)

    val alarmSoundEnabled: StateFlow<Boolean> = prefs.alarmSoundEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserPreferences.DEFAULT_ALARM_SOUND_ENABLED)

    val vibrationEnabled: StateFlow<Boolean> = prefs.vibrationEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserPreferences.DEFAULT_VIBRATION_ENABLED)

    fun setThresholdMultiplier(v: Float) { viewModelScope.launch { prefs.setThresholdMultiplier(v) } }
    fun setFcQuietPeriodS(v: Int)        { viewModelScope.launch { prefs.setFcQuietPeriodS(v) } }
    fun setCarryoverDurationS(v: Int)    { viewModelScope.launch { prefs.setCarryoverDurationS(v) } }
    fun setMinTransientsFc(v: Int)       { viewModelScope.launch { prefs.setMinTransientsFc(v) } }
    fun setMinTransientsSc(v: Int)       { viewModelScope.launch { prefs.setMinTransientsSc(v) } }
    fun setAlarmSoundEnabled(v: Boolean) { viewModelScope.launch { prefs.setAlarmSoundEnabled(v) } }
    fun setVibrationEnabled(v: Boolean)  { viewModelScope.launch { prefs.setVibrationEnabled(v) } }
    fun resetDefaults()                  { viewModelScope.launch { prefs.resetDefaults() } }
}
