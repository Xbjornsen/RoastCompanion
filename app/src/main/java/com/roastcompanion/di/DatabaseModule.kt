package com.roastcompanion.di

import android.content.Context
import androidx.room.Room
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

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): RoastDatabase =
        Room.databaseBuilder(context, RoastDatabase::class.java, RoastDatabase.DATABASE_NAME)
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideRoastSessionDao(db: RoastDatabase): RoastSessionDao = db.roastSessionDao()
}
