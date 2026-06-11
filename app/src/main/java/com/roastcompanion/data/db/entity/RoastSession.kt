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
    val profileName: String = "",

    /** Marked by the user when this roast came out right — used as the reference profile. */
    val isFavorite: Boolean = false,

    /** Cup rating 1–5 after tasting; 0 = unrated. */
    val rating: Int = 0,

    // v3: temperature logging — stored in °C, displayed per user preference
    val fcStartTempC: Float? = null,
    val fcEndTempC: Float? = null,
    val scTempC: Float? = null,

    // v3: bean metadata
    val beanOrigin: String = "",
    val isBlend: Boolean = false,

    // v4: roast parameters
    val roastLevel: String = "",        // "City" | "City+" | "Full City" | "Full City+" | "Vienna" | "French"
    val greenWeightG: Float? = null,    // grams of green coffee charged
    val roastedWeightG: Float? = null,  // grams of roasted coffee out
    val chargeTempC: Float? = null      // drum/air temperature when beans drop in
)
