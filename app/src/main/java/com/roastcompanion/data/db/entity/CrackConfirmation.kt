package com.roastcompanion.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One user-confirmed crack event per roast session.
 * Multiple confirmations of the same type are allowed (user can tap more than once);
 * all of them feed the threshold-learning algorithm.
 *
 * rmsRatio / spectralRatio are captured from the look-back buffer at the moment the
 * user taps confirm — they represent the peak audio event in the preceding 5 seconds,
 * which is typically the crack the user just heard.
 */
@Entity(tableName = "crack_confirmations")
data class CrackConfirmation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    /** "FC_START" | "FC_END" | "SC_START" */
    val crackType: String,
    val confirmedMs: Long,
    val elapsedMs: Long,
    /** Wall time of the app's auto-detection, null if the app missed it entirely. */
    val autoDetectedMs: Long?,
    /** Peak rms/ambient from the 5-second look-back window — the key learning signal. */
    val rmsRatio: Float,
    /** Spectral ratio of the last amplitude-gate-passing frame — secondary signal. */
    val spectralRatio: Float
)
