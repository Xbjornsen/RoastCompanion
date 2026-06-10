package com.roastcompanion.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roastcompanion.data.csv.RoastCsv
import com.roastcompanion.data.preferences.UserPreferences
import com.roastcompanion.data.repository.RoastRepository
import com.roastcompanion.update.UpdateChecker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    object UpToDate : UpdateState()
    data class Available(val info: UpdateChecker.UpdateInfo) : UpdateState()
    data class Downloading(val versionName: String) : UpdateState()
    data class Failed(val message: String) : UpdateState()
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: UserPreferences,
    private val repository: RoastRepository,
    private val updateChecker: UpdateChecker
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

    val minFcTimeMin: StateFlow<Int> = prefs.minFcTimeMin
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserPreferences.DEFAULT_MIN_FC_TIME_MIN)

    val keepScreenOn: StateFlow<Boolean> = prefs.keepScreenOn
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserPreferences.DEFAULT_KEEP_SCREEN_ON)

    val tempUnitCelsius: StateFlow<Boolean> = prefs.tempUnitCelsius
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserPreferences.DEFAULT_TEMP_UNIT_CELSIUS)

    val roasterProfile: StateFlow<String> = prefs.roasterProfile
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserPreferences.DEFAULT_ROASTER_PROFILE)

    /** Roast count for the delete-all confirmation dialog. */
    val sessionCount: StateFlow<Int> = repository.getAllSessions()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    fun setThresholdMultiplier(v: Float) { viewModelScope.launch { prefs.setThresholdMultiplier(v) } }
    fun setFcQuietPeriodS(v: Int)        { viewModelScope.launch { prefs.setFcQuietPeriodS(v) } }
    fun setCarryoverDurationS(v: Int)    { viewModelScope.launch { prefs.setCarryoverDurationS(v) } }
    fun setMinTransientsFc(v: Int)       { viewModelScope.launch { prefs.setMinTransientsFc(v) } }
    fun setMinTransientsSc(v: Int)       { viewModelScope.launch { prefs.setMinTransientsSc(v) } }
    fun setAlarmSoundEnabled(v: Boolean) { viewModelScope.launch { prefs.setAlarmSoundEnabled(v) } }
    fun setVibrationEnabled(v: Boolean)  { viewModelScope.launch { prefs.setVibrationEnabled(v) } }
    fun setMinFcTimeMin(v: Int)          { viewModelScope.launch { prefs.setMinFcTimeMin(v) } }
    fun setKeepScreenOn(v: Boolean)      { viewModelScope.launch { prefs.setKeepScreenOn(v) } }
    fun setTempUnitCelsius(v: Boolean)   { viewModelScope.launch { prefs.setTempUnitCelsius(v) } }
    fun resetDefaults()                  { viewModelScope.launch { prefs.resetDefaults() } }

    fun selectRoaster(profile: com.roastcompanion.data.model.RoasterProfile) {
        viewModelScope.launch {
            prefs.setRoasterProfile(profile.name)
            prefs.setCarryoverDurationS(profile.carryoverSecs)
        }
    }

    // ── CSV export / import ──────────────────────────────────────────

    fun exportHistory(uri: Uri) {
        viewModelScope.launch {
            try {
                val sessions = repository.getAllSessionsOnce()
                if (sessions.isEmpty()) {
                    _messages.emit("No roasts to export yet")
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                        out.write(RoastCsv.serialize(sessions).toByteArray(Charsets.UTF_8))
                    } ?: throw IllegalStateException("Could not open file")
                }
                _messages.emit("Exported ${sessions.size} roasts")
            } catch (e: Exception) {
                _messages.emit("Export failed: ${e.message}")
            }
        }
    }

    fun importHistory(uri: Uri) {
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use {
                        it.bufferedReader(Charsets.UTF_8).readText()
                    } ?: throw IllegalStateException("Could not open file")
                }
                val parsed = RoastCsv.parse(text)
                val imported = repository.importSessions(parsed)
                val skipped = parsed.size - imported
                _messages.emit(
                    when {
                        imported == 0 && skipped > 0 -> "Nothing new — all $skipped roasts already in history"
                        skipped > 0 -> "Imported $imported roasts ($skipped already in history)"
                        else -> "Imported $imported roasts"
                    }
                )
            } catch (e: Exception) {
                _messages.emit("Import failed: ${e.message}")
            }
        }
    }

    fun deleteAllHistory() {
        viewModelScope.launch {
            repository.deleteAllSessions()
            _messages.emit("All roast history deleted")
        }
    }

    // ── App updates ──────────────────────────────────────────────────

    fun checkForUpdate() {
        if (_updateState.value is UpdateState.Checking ||
            _updateState.value is UpdateState.Downloading
        ) return
        _updateState.value = UpdateState.Checking
        viewModelScope.launch {
            try {
                val info = updateChecker.checkForUpdate()
                _updateState.value = if (info != null) UpdateState.Available(info) else UpdateState.UpToDate
            } catch (e: Exception) {
                _updateState.value = UpdateState.Failed(e.message ?: "Check failed")
            }
        }
    }

    fun downloadAndInstallUpdate() {
        val state = _updateState.value as? UpdateState.Available ?: return
        _updateState.value = UpdateState.Downloading(state.info.versionName)
        viewModelScope.launch {
            try {
                val apk = updateChecker.downloadApk(state.info)
                updateChecker.installApk(apk)
                _updateState.value = UpdateState.Available(state.info)
            } catch (e: Exception) {
                _updateState.value = UpdateState.Failed(e.message ?: "Download failed")
            }
        }
    }
}
