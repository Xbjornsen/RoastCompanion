package com.roastcompanion.ui.log

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roastcompanion.data.db.entity.RoastSession
import com.roastcompanion.data.preferences.UserPreferences
import com.roastcompanion.data.repository.RoastRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SessionDetailViewModel @Inject constructor(
    private val repository: RoastRepository,
    private val prefs: UserPreferences
) : ViewModel() {

    private val _session = MutableStateFlow<RoastSession?>(null)
    val session: StateFlow<RoastSession?> = _session.asStateFlow()

    val tempUnitCelsius: StateFlow<Boolean> = prefs.tempUnitCelsius
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserPreferences.DEFAULT_TEMP_UNIT_CELSIUS)

    fun loadSession(id: Long) {
        viewModelScope.launch {
            _session.value = repository.getSessionById(id)
        }
    }

    fun saveNotes(notes: String) {
        val id = _session.value?.id ?: return
        viewModelScope.launch {
            repository.updateNotes(id, notes)
            _session.value = _session.value?.copy(notes = notes)
        }
    }

    fun toggleFavorite() {
        val current = _session.value ?: return
        val newValue = !current.isFavorite
        viewModelScope.launch {
            repository.setFavorite(current.id, newValue)
            _session.value = current.copy(isFavorite = newValue)
        }
    }

    /** Tapping the current rating again clears it. */
    fun setRating(rating: Int) {
        val current = _session.value ?: return
        val newValue = if (current.rating == rating) 0 else rating
        viewModelScope.launch {
            repository.setRating(current.id, newValue)
            _session.value = current.copy(rating = newValue)
        }
    }

    fun setFcStartTemp(tempC: Float?) {
        val current = _session.value ?: return
        viewModelScope.launch {
            repository.updateTemperatures(current.id, tempC, current.fcEndTempC, current.scTempC)
            _session.value = current.copy(fcStartTempC = tempC)
        }
    }

    fun setFcEndTemp(tempC: Float?) {
        val current = _session.value ?: return
        viewModelScope.launch {
            repository.updateTemperatures(current.id, current.fcStartTempC, tempC, current.scTempC)
            _session.value = current.copy(fcEndTempC = tempC)
        }
    }

    fun setScTemp(tempC: Float?) {
        val current = _session.value ?: return
        viewModelScope.launch {
            repository.updateTemperatures(current.id, current.fcStartTempC, current.fcEndTempC, tempC)
            _session.value = current.copy(scTempC = tempC)
        }
    }

    fun setBeanInfo(origin: String, isBlend: Boolean) {
        val current = _session.value ?: return
        viewModelScope.launch {
            repository.updateBeanInfo(current.id, origin, isBlend)
            _session.value = current.copy(beanOrigin = origin, isBlend = isBlend)
        }
    }

    fun setRoastName(name: String) {
        val current = _session.value ?: return
        viewModelScope.launch {
            repository.updateRoastName(current.id, name)
            _session.value = current.copy(profileName = name)
        }
    }

    fun setRoastLevel(level: String) {
        val current = _session.value ?: return
        viewModelScope.launch {
            repository.updateRoastLevel(current.id, level)
            _session.value = current.copy(roastLevel = level)
        }
    }

    fun setWeight(greenG: Float?, roastedG: Float?) {
        val current = _session.value ?: return
        viewModelScope.launch {
            repository.updateWeight(current.id, greenG, roastedG)
            _session.value = current.copy(greenWeightG = greenG, roastedWeightG = roastedG)
        }
    }

    fun setChargeTemp(tempC: Float?) {
        val current = _session.value ?: return
        viewModelScope.launch {
            repository.updateChargeTemp(current.id, tempC)
            _session.value = current.copy(chargeTempC = tempC)
        }
    }
}
