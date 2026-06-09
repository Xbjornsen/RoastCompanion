package com.roastcompanion.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "roast_sessions")
data class RoastSession(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val startTimeMs: Long,

    val firstCrackStartMs: Long? = null,
    val firstCrackEndMs: Long? = null,

    val secondCrackDetectedMs: Long? = null,

    val coolingStartedMs: Long? = null,

    val endTimeMs: Long? = null,

    val firstCrackDurationMs: Long? = null,
    val totalDurationMs: Long? = null,

    val notes: String = "",
    val profileName: String = ""
)
