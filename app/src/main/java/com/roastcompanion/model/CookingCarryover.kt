package com.roastcompanion.model

data class CarryoverState(
    val totalDurationS: Int,
    val remainingS: Int,
    val progressFraction: Float,
    val colorLabel: String,
    val isDone: Boolean
)

object CookingCarryover {

    fun developmentProgress(elapsedS: Int, totalS: Int): Float =
        (elapsedS.toFloat() / totalS.coerceAtLeast(1)).coerceIn(0f, 1f)

    fun remainingSeconds(elapsedS: Int, totalS: Int): Int =
        (totalS - elapsedS).coerceAtLeast(0)

    fun colorLabel(progressFraction: Float): String = when {
        progressFraction < 0.25f -> "City+"
        progressFraction < 0.50f -> "Full City"
        progressFraction < 0.75f -> "Full City+"
        else                     -> "Vienna"
    }

    fun buildState(elapsedS: Int, totalS: Int): CarryoverState {
        val progress = developmentProgress(elapsedS, totalS)
        return CarryoverState(
            totalDurationS  = totalS,
            remainingS      = remainingSeconds(elapsedS, totalS),
            progressFraction = progress,
            colorLabel      = colorLabel(progress),
            isDone          = elapsedS >= totalS
        )
    }
}
