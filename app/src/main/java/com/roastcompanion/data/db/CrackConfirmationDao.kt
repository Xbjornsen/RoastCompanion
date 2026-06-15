package com.roastcompanion.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.roastcompanion.data.db.entity.CrackConfirmation

@Dao
interface CrackConfirmationDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(confirmation: CrackConfirmation): Long

    @Query("SELECT * FROM crack_confirmations WHERE sessionId = :sessionId ORDER BY confirmedMs ASC")
    suspend fun getBySession(sessionId: Long): List<CrackConfirmation>

    /** All rmsRatio values from confirmed crack-start events — used for threshold learning. */
    @Query("""
        SELECT rmsRatio FROM crack_confirmations
        WHERE crackType IN ('FC_START', 'SC_START') AND rmsRatio > 0
        ORDER BY rmsRatio ASC
    """)
    suspend fun getAllCrackRmsRatios(): List<Float>

    @Query("""
        SELECT COUNT(*) FROM crack_confirmations
        WHERE crackType IN ('FC_START', 'SC_START') AND rmsRatio > 0
    """)
    suspend fun getCrackStartCount(): Int
}
