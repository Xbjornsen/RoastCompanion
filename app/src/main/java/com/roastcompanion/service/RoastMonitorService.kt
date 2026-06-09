package com.roastcompanion.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Binder
import android.os.IBinder
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.roastcompanion.audio.AudioAnalyzer
import com.roastcompanion.audio.RoastPhase
import com.roastcompanion.util.NotificationHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class RoastMonitorService : LifecycleService() {

    @Inject lateinit var audioAnalyzer: AudioAnalyzer
    @Inject lateinit var notificationHelper: NotificationHelper

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null

    inner class LocalBinder : Binder() {
        fun getService(): RoastMonitorService = this@RoastMonitorService
        fun getAnalyzer(): AudioAnalyzer = audioAnalyzer
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        notificationHelper.createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP  -> stopRecording()
        }
        return START_STICKY
    }

    private fun startRecording() {
        val notification = notificationHelper.buildRoastNotification()
        startForeground(NotificationHelper.NOTIF_ID_MONITOR, notification)

        val bufferSize = AudioRecord.getMinBufferSize(
            AudioAnalyzer.SAMPLE_RATE,
            AudioAnalyzer.CHANNEL_CONFIG,
            AudioAnalyzer.AUDIO_FORMAT
        ).coerceAtLeast(AudioAnalyzer.SAMPLES_PER_WINDOW * 2)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            AudioAnalyzer.SAMPLE_RATE,
            AudioAnalyzer.CHANNEL_CONFIG,
            AudioAnalyzer.AUDIO_FORMAT,
            bufferSize
        )

        lifecycleScope.launch {
            audioAnalyzer.loadPreferences()
            audioAnalyzer.startSession()
        }

        audioRecord?.startRecording()

        recordingJob = lifecycleScope.launch(Dispatchers.IO) {
            val buffer = ShortArray(AudioAnalyzer.SAMPLES_PER_WINDOW)
            while (isActive) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
                if (read > 0) {
                    audioAnalyzer.processBuffer(buffer, read)
                }
            }
        }

        lifecycleScope.launch {
            audioAnalyzer.phaseFlow.collect { phase ->
                notificationHelper.updateMonitorNotification(phase)
            }
        }
    }

    private fun stopRecording() {
        recordingJob?.cancel()
        recordingJob = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        audioAnalyzer.stopSession()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.roastcompanion.START"
        const val ACTION_STOP  = "com.roastcompanion.STOP"

        fun startIntent(context: Context): Intent =
            Intent(context, RoastMonitorService::class.java).apply { action = ACTION_START }

        fun stopIntent(context: Context): Intent =
            Intent(context, RoastMonitorService::class.java).apply { action = ACTION_STOP }

        fun bindIntent(context: Context): Intent =
            Intent(context, RoastMonitorService::class.java)

        fun buildConnection(
            onConnected: (RoastMonitorService) -> Unit,
            onDisconnected: () -> Unit = {}
        ): ServiceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                onConnected((binder as RoastMonitorService.LocalBinder).getService())
            }
            override fun onServiceDisconnected(name: ComponentName) = onDisconnected()
        }
    }
}
