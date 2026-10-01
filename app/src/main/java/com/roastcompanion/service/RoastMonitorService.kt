package com.roastcompanion.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.roastcompanion.audio.AudioAnalyzer
import com.roastcompanion.audio.CrackEvent
import com.roastcompanion.audio.WavRecorder
import com.roastcompanion.util.CrackAlarm
import com.roastcompanion.util.NotificationHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class RoastMonitorService : LifecycleService() {

    @Inject lateinit var audioAnalyzer: AudioAnalyzer
    @Inject lateinit var notificationHelper: NotificationHelper
    @Inject lateinit var crackAlarm: CrackAlarm

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var wavRecorder: WavRecorder? = null

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
            ACTION_START -> startRecording(
                startTimeMs = intent.getLongExtra(EXTRA_START_TIME_MS, 0L),
                doRecord    = intent.getBooleanExtra(EXTRA_RECORD_FOR_TRAINING, false)
            )
            ACTION_STOP  -> stopRecording()
            ACTION_STOP_ALARM -> { crackAlarm.stop(); if (audioRecord == null) stopSelf() }
            // Restarted by the system after being killed (null intent): the roast
            // state is gone, so don't sit there as an empty foreground service.
            null -> if (audioRecord == null) stopSelf()
        }
        // Not sticky: a restart can't resume the roast (session/VM state lives
        // in the killed process), and a silent do-nothing service is worse.
        return START_NOT_STICKY
    }

    private fun startRecording(startTimeMs: Long, doRecord: Boolean) {
        val notification = notificationHelper.buildRoastNotification()
        startForeground(NotificationHelper.NOTIF_ID_MONITOR, notification)

        if (doRecord && startTimeMs > 0L) {
            try {
                val dir = getExternalFilesDir("training") ?: filesDir.resolve("training")
                dir.mkdirs()
                val wav = File(dir, "training_$startTimeMs.wav")
                wavRecorder = WavRecorder(wav, AudioAnalyzer.SAMPLE_RATE)
                wavRecorder?.open()
                Log.i("RC", "WAV recording: ${wav.absolutePath}")
            } catch (e: Exception) {
                Log.e("RC", "WAV recorder failed to open: ${e.message}")
                wavRecorder = null
            }
        }

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
                    wavRecorder?.write(buffer, read)
                }
            }
        }

        // Crack alerts belong to the service so they fire with the screen off /
        // app in the background (they used to live in RoastFragment).
        lifecycleScope.launch {
            audioAnalyzer.eventFlow.collect { event ->
                when (event) {
                    is CrackEvent.FirstCrackStarted  -> crackAlarm.firstCrack()
                    is CrackEvent.SecondCrackStarted -> crackAlarm.secondCrack()
                    else -> Unit
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
        crackAlarm.stop()
        recordingJob?.cancel()
        recordingJob = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        try {
            val bytes = wavRecorder?.close() ?: 0L
            if (bytes > 0L) Log.i("RC", "WAV saved: ${bytes / 1_048_576} MB")
        } catch (e: Exception) {
            Log.e("RC", "Error closing WAV: ${e.message}")
        }
        wavRecorder = null
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
        const val ACTION_STOP_ALARM = "com.roastcompanion.STOP_ALARM"

        const val EXTRA_START_TIME_MS        = "startTimeMs"
        const val EXTRA_RECORD_FOR_TRAINING  = "recordForTraining"

        fun startIntent(context: Context, startTimeMs: Long, doRecord: Boolean): Intent =
            Intent(context, RoastMonitorService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_START_TIME_MS, startTimeMs)
                putExtra(EXTRA_RECORD_FOR_TRAINING, doRecord)
            }

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
