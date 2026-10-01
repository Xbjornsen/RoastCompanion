package com.roastcompanion.util

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.content.getSystemService
import com.roastcompanion.data.preferences.UserPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Crack alerts (sound + vibration + heads-up notification), owned by the
 * foreground service so they fire with the screen off or the app in the
 * background. Previously they lived in RoastFragment and were silently dropped
 * whenever the Roast screen wasn't visible. Honours the Settings toggles
 * (alarm sound / vibration), which were previously ignored.
 */
@Singleton
class CrackAlarm @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: UserPreferences,
    private val notifications: NotificationHelper
) {
    companion object {
        private const val TAG = "RC"
        /** Safety cap: stop ringing after this long if nobody dismisses it. */
        const val MAX_RING_MS = 120_000L
    }

    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private val stopRunnable = Runnable { stop() }

    suspend fun firstCrack() {
        if (prefs.vibrationEnabled.first()) vibrate(longArrayOf(0, 200, 100, 200))
    }

    /** Second crack: loop the alarm until [stop] (or MAX_RING_MS), vibrate, notify. */
    suspend fun secondCrack() {
        val sound = prefs.alarmSoundEnabled.first()
        val vib = prefs.vibrationEnabled.first()
        notifications.fireSecondCrackAlarm()
        if (vib) vibrate(longArrayOf(0, 500, 200, 500, 200, 500))
        if (sound) main.post { startSound() }
    }

    fun stop() {
        main.removeCallbacks(stopRunnable)
        main.post {
            player?.let { runCatching { if (it.isPlaying) it.stop() }; it.release() }
            player = null
            notifications.cancelSecondCrackAlarm()
        }
    }

    private fun startSound() {
        player?.release()
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        player = try {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(context, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "alarm sound failed: ${e.message}")
            null
        }
        main.removeCallbacks(stopRunnable)
        main.postDelayed(stopRunnable, MAX_RING_MS)
    }

    private fun vibrate(pattern: LongArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService<VibratorManager>()?.defaultVibrator
                ?.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService<Vibrator>()?.vibrate(pattern, -1)
        }
    }
}
