package com.roastcompanion.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.roastcompanion.data.db.entity.CrackConfirmation
import com.roastcompanion.data.db.entity.RoastSession

@Database(
    entities = [RoastSession::class, CrackConfirmation::class],
    version = 5,
    exportSchema = true
)
abstract class RoastDatabase : RoomDatabase() {

    abstract fun roastSessionDao(): RoastSessionDao
    abstract fun crackConfirmationDao(): CrackConfirmationDao

    companion object {
        const val DATABASE_NAME = "roast_companion.db"
    }
}
