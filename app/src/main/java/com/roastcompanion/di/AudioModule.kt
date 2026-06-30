package com.roastcompanion.di

import android.content.Context
import com.roastcompanion.audio.AudioAnalyzer
import com.roastcompanion.audio.CrackClassifier
import com.roastcompanion.audio.TransientDetector
import com.roastcompanion.data.preferences.UserPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AudioModule {

    @Provides
    @Singleton
    fun provideTransientDetector(): TransientDetector = TransientDetector()

    @Provides
    @Singleton
    fun provideCrackClassifier(@ApplicationContext context: Context): CrackClassifier =
        CrackClassifier(context)

    @Provides
    @Singleton
    fun provideAudioAnalyzer(
        detector: TransientDetector,
        prefs: UserPreferences,
        crackClassifier: CrackClassifier
    ): AudioAnalyzer = AudioAnalyzer(detector, prefs, crackClassifier)
}
