package com.roastcompanion.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.roastcompanion.data.db.entity.RoastSession

@Database(
    entities = [RoastSession::class],
    version = 2,
    exportSchema = true
)
abstract class RoastDatabase : RoomDatabase() {

    abstract fun roastSessionDao(): RoastSessionDao

    companion object {
        const val DATABASE_NAME = "roast_companion.db"
    }
}
