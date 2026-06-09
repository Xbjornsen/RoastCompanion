package com.roastcompanion.ui.log

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roastcompanion.data.db.entity.RoastSession
import com.roastcompanion.data.repository.RoastRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RoastLogViewModel @Inject constructor(
    private val repository: RoastRepository
) : ViewModel() {

    val sessions: StateFlow<List<RoastSession>> = repository.getAllSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var lastDeleted: RoastSession? = null

    fun deleteSession(session: RoastSession) {
        lastDeleted = session
        viewModelScope.launch {
            repository.deleteSession(session.id)
        }
    }

    fun undoDelete() {
        val session = lastDeleted ?: return
        lastDeleted = null
        viewModelScope.launch {
            repository.createSession(session.startTimeMs)
        }
    }
}
