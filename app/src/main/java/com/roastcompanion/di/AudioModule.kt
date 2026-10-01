package com.roastcompanion.di

import com.roastcompanion.audio.AudioAnalyzer
import com.roastcompanion.audio.TransientDetector
import com.roastcompanion.data.preferences.UserPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
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
    fun provideAudioAnalyzer(
        detector: TransientDetector,
        prefs: UserPreferences
    ): AudioAnalyzer = AudioAnalyzer(detector, prefs)
}
