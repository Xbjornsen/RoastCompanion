package com.roastcompanion.data.db

import androidx.room.*
import com.roastcompanion.data.db.entity.RoastSession
import kotlinx.coroutines.flow.Flow

@Dao
interface RoastSessionDao {

    @Insert
    suspend fun insert(session: RoastSession): Long

    @Update
    suspend fun update(session: RoastSession)

    @Query("SELECT * FROM roast_sessions ORDER BY startTimeMs DESC")
    fun getAllSessions(): Flow<List<RoastSession>>

    @Query("SELECT * FROM roast_sessions WHERE id = :id")
    suspend fun getSessionById(id: Long): RoastSession?

    @Query("DELETE FROM roast_sessions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM roast_sessions")
    suspend fun deleteAll()

    @Query("SELECT * FROM roast_sessions ORDER BY startTimeMs DESC")
    suspend fun getAllSessionsOnce(): List<RoastSession>

    @Insert
    suspend fun insertAll(sessions: List<RoastSession>)

    @Query("SELECT startTimeMs FROM roast_sessions")
    suspend fun getAllStartTimes(): List<Long>

    /** Most recently favourited roast — the live reference profile. */
    @Query("SELECT * FROM roast_sessions WHERE isFavorite = 1 ORDER BY startTimeMs DESC LIMIT 1")
    fun getLatestFavorite(): Flow<RoastSession?>

    // ── Autocomplete suggestions: distinct values previously entered, most-recent first ──

    @Query("SELECT profileName FROM roast_sessions WHERE profileName != '' GROUP BY profileName ORDER BY MAX(startTimeMs) DESC")
    suspend fun getRoastNameSuggestions(): List<String>

    @Query("SELECT beanOrigin FROM roast_sessions WHERE beanOrigin != '' GROUP BY beanOrigin ORDER BY MAX(startTimeMs) DESC")
    suspend fun getBeanOriginSuggestions(): List<String>

    @Query("SELECT greenWeightG FROM roast_sessions WHERE greenWeightG IS NOT NULL GROUP BY greenWeightG ORDER BY MAX(startTimeMs) DESC")
    suspend fun getGreenWeightSuggestions(): List<Float>
}
