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

    suspend fun updateNotes(id: Long, notes: String) {
        dao.getSessionById(id)?.let {
            dao.update(it.copy(notes = notes))
        }
    }

    suspend fun getSessionById(id: Long): RoastSession? = dao.getSessionById(id)
}
