package com.roastcompanion.ui.log

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roastcompanion.data.db.entity.RoastSession
import com.roastcompanion.data.repository.RoastRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SessionDetailViewModel @Inject constructor(
    private val repository: RoastRepository
) : ViewModel() {

    private val _session = MutableStateFlow<RoastSession?>(null)
    val session: StateFlow<RoastSession?> = _session.asStateFlow()

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
}
