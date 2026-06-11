package com.roastcompanion.ui.log

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roastcompanion.data.db.entity.RoastSession
import com.roastcompanion.data.repository.RoastRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

enum class SessionFilter { ALL, THIS_WEEK, FC_ONLY, FAVOURITES }

data class HistoryStats(
    val thisMonth: Int = 0,
    val avgFcMs: Long? = null,
    val total: Int = 0
)

@HiltViewModel
class RoastLogViewModel @Inject constructor(
    private val repository: RoastRepository
) : ViewModel() {

    private val allSessions: StateFlow<List<RoastSession>> = repository.getAllSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _filter = MutableStateFlow(SessionFilter.ALL)
    val filter: StateFlow<SessionFilter> = _filter.asStateFlow()

    val sessions: StateFlow<List<RoastSession>> =
        combine(allSessions, _searchQuery, _filter) { list, query, filter ->
            list.filter { session ->
                matchesFilter(session, filter) && matchesQuery(session, query)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val stats: StateFlow<HistoryStats> = allSessions
        .map { list ->
            val monthStart = Calendar.getInstance().apply {
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            // Avg time from roast start to first crack, across sessions that logged FC
            val fcDeltas = list.mapNotNull { s ->
                s.firstCrackStartMs?.let { it - s.startTimeMs }
            }

            HistoryStats(
                thisMonth = list.count { it.startTimeMs >= monthStart },
                avgFcMs = if (fcDeltas.isEmpty()) null else fcDeltas.average().toLong(),
                total = list.size
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HistoryStats())

    private var lastDeleted: RoastSession? = null

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setFilter(filter: SessionFilter) {
        _filter.value = filter
    }

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
            repository.restoreSession(session)
        }
    }

    private fun matchesFilter(session: RoastSession, filter: SessionFilter): Boolean =
        when (filter) {
            SessionFilter.ALL -> true
            SessionFilter.THIS_WEEK -> {
                val weekAgo = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
                session.startTimeMs >= weekAgo
            }
            SessionFilter.FC_ONLY -> session.firstCrackStartMs != null
            SessionFilter.FAVOURITES -> session.isFavorite
        }

    private fun matchesQuery(session: RoastSession, query: String): Boolean {
        if (query.isBlank()) return true
        val q = query.trim()
        return session.profileName.contains(q, ignoreCase = true) ||
                session.beanOrigin.contains(q, ignoreCase = true) ||
                session.roastLevel.contains(q, ignoreCase = true) ||
                session.notes.contains(q, ignoreCase = true)
    }
}
