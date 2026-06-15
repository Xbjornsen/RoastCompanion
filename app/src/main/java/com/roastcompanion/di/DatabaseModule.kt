package com.roastcompanion.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.roastcompanion.data.db.CrackConfirmationDao
import com.roastcompanion.data.db.RoastDatabase
import com.roastcompanion.data.db.RoastSessionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    // v2: favourite flag + cup rating for the roast feedback loop
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE roast_sessions ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE roast_sessions ADD COLUMN rating INTEGER NOT NULL DEFAULT 0")
        }
    }

    // v3: temperature logging at FC/SC events + bean origin / blend flag
    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE roast_sessions ADD COLUMN fcStartTempC REAL")
            db.execSQL("ALTER TABLE roast_sessions ADD COLUMN fcEndTempC REAL")
            db.execSQL("ALTER TABLE roast_sessions ADD COLUMN scTempC REAL")
            db.execSQL("ALTER TABLE roast_sessions ADD COLUMN beanOrigin TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE roast_sessions ADD COLUMN isBlend INTEGER NOT NULL DEFAULT 0")
        }
    }

    // v4: roast level, green/roasted weight, charge temperature
    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE roast_sessions ADD COLUMN roastLevel TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE roast_sessions ADD COLUMN greenWeightG REAL")
            db.execSQL("ALTER TABLE roast_sessions ADD COLUMN roastedWeightG REAL")
            db.execSQL("ALTER TABLE roast_sessions ADD COLUMN chargeTempC REAL")
        }
    }

    // v5: crack confirmation table for user feedback + adaptive threshold learning
    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS crack_confirmations (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    sessionId INTEGER NOT NULL,
                    crackType TEXT NOT NULL,
                    confirmedMs INTEGER NOT NULL,
                    elapsedMs INTEGER NOT NULL,
                    autoDetectedMs INTEGER,
                    rmsRatio REAL NOT NULL,
                    spectralRatio REAL NOT NULL
                )
            """)
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): RoastDatabase =
        Room.databaseBuilder(context, RoastDatabase::class.java, RoastDatabase.DATABASE_NAME)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .build()

    @Provides
    fun provideRoastSessionDao(db: RoastDatabase): RoastSessionDao = db.roastSessionDao()

    @Provides
    fun provideCrackConfirmationDao(db: RoastDatabase): CrackConfirmationDao = db.crackConfirmationDao()
}
