package com.roastcompanion.data.repository

import com.roastcompanion.data.db.RoastSessionDao
import com.roastcompanion.data.db.entity.RoastSession
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoastRepository @Inject constructor(
    private val dao: RoastSessionDao
) {
    fun getAllSessions(): Flow<List<RoastSession>> = dao.getAllSessions()

    suspend fun createSession(startTimeMs: Long): Long =
        dao.insert(RoastSession(startTimeMs = startTimeMs))

    suspend fun updateFirstCrackStart(id: Long, timeMs: Long) {
        dao.getSessionById(id)?.let {
            dao.update(it.copy(firstCrackStartMs = timeMs))
        }
    }

    suspend fun updateFirstCrackEnd(id: Long, timeMs: Long, durationMs: Long) {
        dao.getSessionById(id)?.let {
            dao.update(it.copy(firstCrackEndMs = timeMs, firstCrackDurationMs = durationMs))
        }
    }

    suspend fun updateSecondCrack(id: Long, timeMs: Long) {
        dao.getSessionById(id)?.let {
            dao.update(it.copy(secondCrackDetectedMs = timeMs))
        }
    }

    suspend fun updateCoolingStart(id: Long, timeMs: Long) {
        dao.getSessionById(id)?.let {
            dao.update(it.copy(coolingStartedMs = timeMs))
        }
    }

    suspend fun endSession(id: Long, endTimeMs: Long, startTimeMs: Long) {
        dao.getSessionById(id)?.let {
            dao.update(
                it.copy(
                    endTimeMs = endTimeMs,
                    totalDurationMs = endTimeMs - startTimeMs
                )
            )
        }
    }

    suspend fun deleteSession(id: Long) = dao.deleteById(id)

    /** Re-insert a previously deleted session with all its data intact (swipe undo). */
    suspend fun restoreSession(session: RoastSession): Long = dao.insert(session)

    suspend fun updateNotes(id: Long, notes: String) {
        dao.getSessionById(id)?.let {
            dao.update(it.copy(notes = notes))
        }
    }

    suspend fun getSessionById(id: Long): RoastSession? = dao.getSessionById(id)

    suspend fun deleteAllSessions() = dao.deleteAll()

    suspend fun getAllSessionsOnce(): List<RoastSession> = dao.getAllSessionsOnce()

    /**
     * Insert imported sessions, skipping any whose startTimeMs already exists
     * (re-importing the same CSV must not duplicate history).
     * Returns the number actually inserted.
     */
    suspend fun importSessions(sessions: List<RoastSession>): Int {
        val existing = dao.getAllStartTimes().toHashSet()
        val fresh = sessions
            .filter { it.startTimeMs !in existing }
            .map { it.copy(id = 0) }
        if (fresh.isNotEmpty()) dao.insertAll(fresh)
        return fresh.size
    }

    suspend fun setFavorite(id: Long, favorite: Boolean) {
        dao.getSessionById(id)?.let {
            dao.update(it.copy(isFavorite = favorite))
        }
    }

    suspend fun setRating(id: Long, rating: Int) {
        dao.getSessionById(id)?.let {
            dao.update(it.copy(rating = rating))
        }
    }

    fun getLatestFavorite(): Flow<RoastSession?> = dao.getLatestFavorite()

    suspend fun updateTemperatures(id: Long, fcStartTempC: Float?, fcEndTempC: Float?, scTempC: Float?) {
        dao.getSessionById(id)?.let {
            dao.update(it.copy(fcStartTempC = fcStartTempC, fcEndTempC = fcEndTempC, scTempC = scTempC))
        }
    }

    suspend fun updateBeanInfo(id: Long, origin: String, isBlend: Boolean) {
        dao.getSessionById(id)?.let {
            dao.update(it.copy(beanOrigin = origin, isBlend = isBlend))
        }
    }
}
