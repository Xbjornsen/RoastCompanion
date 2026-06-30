package com.roastcompanion.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "roast_settings")

@Singleton
class UserPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        val KEY_THRESHOLD_MULTIPLIER = floatPreferencesKey("threshold_multiplier")
        val KEY_FC_QUIET_PERIOD_S    = intPreferencesKey("fc_quiet_period_s")
        val KEY_CARRYOVER_DURATION_S = intPreferencesKey("carryover_duration_s")
        val KEY_MIN_TRANSIENTS_FC    = intPreferencesKey("min_transients_fc")
        val KEY_MIN_TRANSIENTS_SC    = intPreferencesKey("min_transients_sc")
        val KEY_ALARM_SOUND_ENABLED  = booleanPreferencesKey("alarm_sound_enabled")
        val KEY_VIBRATION_ENABLED    = booleanPreferencesKey("vibration_enabled")
        val KEY_MIN_FC_TIME_MIN      = intPreferencesKey("min_fc_time_min")
        val KEY_KEEP_SCREEN_ON       = booleanPreferencesKey("keep_screen_on")
        val KEY_TEMP_UNIT_CELSIUS    = booleanPreferencesKey("temp_unit_celsius")
        val KEY_ROASTER_PROFILE        = stringPreferencesKey("roaster_profile")
        val KEY_RECORD_FOR_TRAINING    = booleanPreferencesKey("record_for_training")

        const val DEFAULT_THRESHOLD_MULTIPLIER = 1.5f
        const val DEFAULT_FC_QUIET_PERIOD_S    = 25
        const val DEFAULT_CARRYOVER_DURATION_S = 45
        const val DEFAULT_MIN_TRANSIENTS_FC    = 2
        const val DEFAULT_MIN_TRANSIENTS_SC    = 2
        const val DEFAULT_ALARM_SOUND_ENABLED  = true
        const val DEFAULT_VIBRATION_ENABLED    = true
        const val DEFAULT_MIN_FC_TIME_MIN      = 9
        const val DEFAULT_KEEP_SCREEN_ON       = true
        const val DEFAULT_TEMP_UNIT_CELSIUS    = true
        const val DEFAULT_ROASTER_PROFILE      = "Gene Cafe CBR-101"
        const val DEFAULT_RECORD_FOR_TRAINING  = false
    }

    val thresholdMultiplier: Flow<Float> = context.dataStore.data.map {
        it[KEY_THRESHOLD_MULTIPLIER] ?: DEFAULT_THRESHOLD_MULTIPLIER
    }
    val fcQuietPeriodS: Flow<Int> = context.dataStore.data.map {
        it[KEY_FC_QUIET_PERIOD_S] ?: DEFAULT_FC_QUIET_PERIOD_S
    }
    val carryoverDurationS: Flow<Int> = context.dataStore.data.map {
        it[KEY_CARRYOVER_DURATION_S] ?: DEFAULT_CARRYOVER_DURATION_S
    }
    val minTransientsFc: Flow<Int> = context.dataStore.data.map {
        it[KEY_MIN_TRANSIENTS_FC] ?: DEFAULT_MIN_TRANSIENTS_FC
    }
    val minTransientsSc: Flow<Int> = context.dataStore.data.map {
        it[KEY_MIN_TRANSIENTS_SC] ?: DEFAULT_MIN_TRANSIENTS_SC
    }
    val alarmSoundEnabled: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_ALARM_SOUND_ENABLED] ?: DEFAULT_ALARM_SOUND_ENABLED
    }
    val vibrationEnabled: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_VIBRATION_ENABLED] ?: DEFAULT_VIBRATION_ENABLED
    }
    val minFcTimeMin: Flow<Int> = context.dataStore.data.map {
        it[KEY_MIN_FC_TIME_MIN] ?: DEFAULT_MIN_FC_TIME_MIN
    }
    val keepScreenOn: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_KEEP_SCREEN_ON] ?: DEFAULT_KEEP_SCREEN_ON
    }
    val tempUnitCelsius: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_TEMP_UNIT_CELSIUS] ?: DEFAULT_TEMP_UNIT_CELSIUS
    }
    val roasterProfile: Flow<String> = context.dataStore.data.map {
        it[KEY_ROASTER_PROFILE] ?: DEFAULT_ROASTER_PROFILE
    }
    val recordForTraining: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_RECORD_FOR_TRAINING] ?: DEFAULT_RECORD_FOR_TRAINING
    }

    suspend fun setThresholdMultiplier(value: Float) {
        context.dataStore.edit { it[KEY_THRESHOLD_MULTIPLIER] = value }
    }
    suspend fun setFcQuietPeriodS(value: Int) {
        context.dataStore.edit { it[KEY_FC_QUIET_PERIOD_S] = value }
    }
    suspend fun setCarryoverDurationS(value: Int) {
        context.dataStore.edit { it[KEY_CARRYOVER_DURATION_S] = value }
    }
    suspend fun setMinTransientsFc(value: Int) {
        context.dataStore.edit { it[KEY_MIN_TRANSIENTS_FC] = value }
    }
    suspend fun setMinTransientsSc(value: Int) {
        context.dataStore.edit { it[KEY_MIN_TRANSIENTS_SC] = value }
    }
    suspend fun setAlarmSoundEnabled(value: Boolean) {
        context.dataStore.edit { it[KEY_ALARM_SOUND_ENABLED] = value }
    }
    suspend fun setVibrationEnabled(value: Boolean) {
        context.dataStore.edit { it[KEY_VIBRATION_ENABLED] = value }
    }
    suspend fun setMinFcTimeMin(value: Int) {
        context.dataStore.edit { it[KEY_MIN_FC_TIME_MIN] = value }
    }
    suspend fun setKeepScreenOn(value: Boolean) {
        context.dataStore.edit { it[KEY_KEEP_SCREEN_ON] = value }
    }
    suspend fun setTempUnitCelsius(value: Boolean) {
        context.dataStore.edit { it[KEY_TEMP_UNIT_CELSIUS] = value }
    }
    suspend fun setRoasterProfile(value: String) {
        context.dataStore.edit { it[KEY_ROASTER_PROFILE] = value }
    }
    suspend fun setRecordForTraining(value: Boolean) {
        context.dataStore.edit { it[KEY_RECORD_FOR_TRAINING] = value }
    }
    suspend fun resetDefaults() {
        context.dataStore.edit { prefs ->
            prefs[KEY_THRESHOLD_MULTIPLIER] = DEFAULT_THRESHOLD_MULTIPLIER
            prefs[KEY_FC_QUIET_PERIOD_S]    = DEFAULT_FC_QUIET_PERIOD_S
            prefs[KEY_CARRYOVER_DURATION_S] = DEFAULT_CARRYOVER_DURATION_S
            prefs[KEY_MIN_TRANSIENTS_FC]    = DEFAULT_MIN_TRANSIENTS_FC
            prefs[KEY_MIN_TRANSIENTS_SC]    = DEFAULT_MIN_TRANSIENTS_SC
            prefs[KEY_ALARM_SOUND_ENABLED]  = DEFAULT_ALARM_SOUND_ENABLED
            prefs[KEY_VIBRATION_ENABLED]    = DEFAULT_VIBRATION_ENABLED
            prefs[KEY_MIN_FC_TIME_MIN]      = DEFAULT_MIN_FC_TIME_MIN
            prefs[KEY_KEEP_SCREEN_ON]       = DEFAULT_KEEP_SCREEN_ON
        }
    }
}
