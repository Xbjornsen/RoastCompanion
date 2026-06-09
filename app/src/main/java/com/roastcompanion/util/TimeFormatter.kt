package com.roastcompanion.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TimeFormatter {

    fun formatElapsed(elapsedMs: Long): String {
        val totalSeconds = elapsedMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d".format(minutes, seconds)
    }

    fun formatTimestamp(epochMs: Long): String {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(epochMs))
    }

    fun formatDate(epochMs: Long): String {
        val sdf = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
        return sdf.format(Date(epochMs))
    }

    fun formatDuration(durationMs: Long): String {
        val totalSeconds = durationMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
    }
}
