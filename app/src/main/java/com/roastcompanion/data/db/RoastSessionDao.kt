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
}
