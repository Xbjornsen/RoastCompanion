package com.roastcompanion.data.repository

import com.roastcompanion.data.db.CrackConfirmationDao
import com.roastcompanion.data.db.RoastSessionDao
import com.roastcompanion.data.db.entity.CrackConfirmation
import com.roastcompanion.data.db.entity.RoastSession
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoastRepository @Inject constructor(
    private val dao: RoastSessionDao,
    private val confirmDao: CrackConfirmationDao
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

    suspend fun getRoastNameSuggestions(): List<String> = dao.getRoastNameSuggestions()
    suspend fun getBeanOriginSuggestions(): List<String> = dao.getBeanOriginSuggestions()
    suspend fun getGreenWeightSuggestions(): List<Float> = dao.getGreenWeightSuggestions()

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

    suspend fun updateRoastName(id: Long, name: String) {
        dao.getSessionById(id)?.let {
            dao.update(it.copy(profileName = name))
        }
    }

    suspend fun updateRoastLevel(id: Long, level: String) {
        dao.getSessionById(id)?.let {
            dao.update(it.copy(roastLevel = level))
        }
    }

    suspend fun updateWeight(id: Long, greenG: Float?, roastedG: Float?) {
        dao.getSessionById(id)?.let {
            dao.update(it.copy(greenWeightG = greenG, roastedWeightG = roastedG))
        }
    }

    suspend fun updateChargeTemp(id: Long, tempC: Float?) {
        dao.getSessionById(id)?.let {
            dao.update(it.copy(chargeTempC = tempC))
        }
    }

    // ── Crack confirmation + adaptive learning ────────────────────────────

    suspend fun confirmCrack(
        sessionId: Long,
        crackType: String,
        confirmedMs: Long,
        elapsedMs: Long,
        autoDetectedMs: Long?,
        rmsRatio: Float,
        spectralRatio: Float
    ) {
        confirmDao.insert(
            CrackConfirmation(
                sessionId = sessionId,
                crackType = crackType,
                confirmedMs = confirmedMs,
                elapsedMs = elapsedMs,
                autoDetectedMs = autoDetectedMs,
                rmsRatio = rmsRatio,
                spectralRatio = spectralRatio
            )
        )
    }

    /**
     * Computes a suggested amplitude-gate threshold from all confirmed crack events.
     * Returns null until [MIN_CONFIRM_EXAMPLES] examples have been collected.
     *
     * Method: sort all confirmed rmsRatios ascending, pick the 20th percentile value
     * and apply a 5% safety margin below it. This catches ~80% of your past cracks
     * while staying above pure motor noise.
     */
    suspend fun computeSuggestedThreshold(): Float? {
        val count = confirmDao.getCrackStartCount()
        if (count < MIN_CONFIRM_EXAMPLES) return null
        val ratios = confirmDao.getAllCrackRmsRatios()   // already sorted ASC by the DAO query
        val p20idx = (ratios.size * 0.20).toInt().coerceIn(0, ratios.size - 1)
        return (ratios[p20idx] * 0.95f).coerceAtLeast(1.0f)
    }

    suspend fun confirmationCount(): Int = confirmDao.getCrackStartCount()

    suspend fun getConfirmationsForSession(sessionId: Long) =
        confirmDao.getBySession(sessionId)

    companion object {
        const val MIN_CONFIRM_EXAMPLES = 5
    }
}
