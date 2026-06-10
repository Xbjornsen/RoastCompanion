package com.roastcompanion.data.csv

import com.roastcompanion.data.db.entity.RoastSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * RFC-4180 CSV export/import for roast history.
 *
 * The `startDate` column is for spreadsheet readability only — import keys
 * off `startTimeMs` and ignores it. Unknown columns are tolerated and column
 * order doesn't matter, so files edited in a spreadsheet still re-import.
 */
object RoastCsv {

    private val COLUMNS = listOf(
        "startDate", "startTimeMs",
        "firstCrackStartMs", "firstCrackEndMs",
        "secondCrackDetectedMs", "coolingStartedMs", "endTimeMs",
        "firstCrackDurationMs", "totalDurationMs",
        "favorite", "rating",
        "profileName", "notes",
        "fcStartTempC", "fcEndTempC", "scTempC",
        "beanOrigin", "isBlend"
    )

    private fun dateFormat() = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    fun serialize(sessions: List<RoastSession>): String {
        val sb = StringBuilder()
        sb.append(COLUMNS.joinToString(",")).append("\r\n")
        val fmt = dateFormat()
        for (s in sessions) {
            val fields = listOf(
                fmt.format(Date(s.startTimeMs)),
                s.startTimeMs.toString(),
                s.firstCrackStartMs?.toString() ?: "",
                s.firstCrackEndMs?.toString() ?: "",
                s.secondCrackDetectedMs?.toString() ?: "",
                s.coolingStartedMs?.toString() ?: "",
                s.endTimeMs?.toString() ?: "",
                s.firstCrackDurationMs?.toString() ?: "",
                s.totalDurationMs?.toString() ?: "",
                if (s.isFavorite) "1" else "0",
                s.rating.toString(),
                s.profileName,
                s.notes,
                s.fcStartTempC?.toString() ?: "",
                s.fcEndTempC?.toString() ?: "",
                s.scTempC?.toString() ?: "",
                s.beanOrigin,
                if (s.isBlend) "1" else "0"
            )
            sb.append(fields.joinToString(",") { escape(it) }).append("\r\n")
        }
        return sb.toString()
    }

    /**
     * Parse a previously exported file. Returns the sessions found.
     * @throws IllegalArgumentException if the file has no RoastCompanion header.
     */
    fun parse(text: String): List<RoastSession> {
        val records = splitRecords(text)
        if (records.isEmpty()) throw IllegalArgumentException("Empty file")

        val header = records.first().map { it.trim() }
        val idx = header.withIndex().associate { (i, name) -> name to i }
        if ("startTimeMs" !in idx) {
            throw IllegalArgumentException("Not a RoastCompanion export (missing startTimeMs column)")
        }

        fun List<String>.col(name: String): String? =
            idx[name]?.let { getOrNull(it) }?.takeIf { it.isNotBlank() }

        return records.drop(1).mapNotNull { row ->
            val start = row.col("startTimeMs")?.toLongOrNull() ?: return@mapNotNull null
            RoastSession(
                startTimeMs = start,
                firstCrackStartMs = row.col("firstCrackStartMs")?.toLongOrNull(),
                firstCrackEndMs = row.col("firstCrackEndMs")?.toLongOrNull(),
                secondCrackDetectedMs = row.col("secondCrackDetectedMs")?.toLongOrNull(),
                coolingStartedMs = row.col("coolingStartedMs")?.toLongOrNull(),
                endTimeMs = row.col("endTimeMs")?.toLongOrNull(),
                firstCrackDurationMs = row.col("firstCrackDurationMs")?.toLongOrNull(),
                totalDurationMs = row.col("totalDurationMs")?.toLongOrNull(),
                isFavorite = row.col("favorite") == "1",
                rating = (row.col("rating")?.toIntOrNull() ?: 0).coerceIn(0, 5),
                profileName = row.col("profileName") ?: "",
                notes = row.col("notes") ?: "",
                fcStartTempC = row.col("fcStartTempC")?.toFloatOrNull(),
                fcEndTempC = row.col("fcEndTempC")?.toFloatOrNull(),
                scTempC = row.col("scTempC")?.toFloatOrNull(),
                beanOrigin = row.col("beanOrigin") ?: "",
                isBlend = row.col("isBlend") == "1"
            )
        }
    }

    private fun escape(field: String): String =
        if (field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + field.replace("\"", "\"\"") + "\""
        } else field

    /** Quote-aware record splitter — handles commas and newlines inside quoted fields. */
    private fun splitRecords(text: String): List<List<String>> {
        val records = mutableListOf<List<String>>()
        var fields = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes -> when {
                    c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { field.append('"'); i++ }
                    c == '"' -> inQuotes = false
                    else -> field.append(c)
                }
                c == '"' -> inQuotes = true
                c == ',' -> { fields.add(field.toString()); field.clear() }
                c == '\r' || c == '\n' -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    fields.add(field.toString()); field.clear()
                    if (fields.any { it.isNotEmpty() }) records.add(fields)
                    fields = mutableListOf()
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || fields.isNotEmpty()) {
            fields.add(field.toString())
            if (fields.any { it.isNotEmpty() }) records.add(fields)
        }
        return records
    }
}
