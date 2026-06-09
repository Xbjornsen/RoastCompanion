package com.roastcompanion.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.roastcompanion.R
import com.roastcompanion.audio.RoastPhase
import com.roastcompanion.ui.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val CHANNEL_MONITOR = "roast_monitor"
        const val CHANNEL_ALARM   = "crack_alarm"
        const val NOTIF_ID_MONITOR = 1001
        const val NOTIF_ID_ALARM   = 1002
    }

    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun createChannels() {
        val monitorChannel = NotificationChannel(
            CHANNEL_MONITOR,
            context.getString(R.string.notif_channel_monitor),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Ongoing roast monitoring status"
            setShowBadge(false)
        }
        val alarmChannel = NotificationChannel(
            CHANNEL_ALARM,
            context.getString(R.string.notif_channel_alarm),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Crack detection alarm"
            enableVibration(true)
        }
        manager.createNotificationChannel(monitorChannel)
        manager.createNotificationChannel(alarmChannel)
    }

    private fun mainActivityIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun buildRoastNotification(text: String = context.getString(R.string.notif_monitoring)): Notification =
        NotificationCompat.Builder(context, CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(mainActivityIntent())
            .build()

    fun updateMonitorNotification(phase: RoastPhase) {
        val text = when (phase) {
            RoastPhase.MONITORING             -> context.getString(R.string.notif_monitoring)
            RoastPhase.FIRST_CRACK_ACTIVE     -> context.getString(R.string.notif_fc_active)
            RoastPhase.FIRST_CRACK_COMPLETE   -> context.getString(R.string.notif_monitoring)
            RoastPhase.SECOND_CRACK_ACTIVE    -> context.getString(R.string.notif_sc_active)
            RoastPhase.COOLING                -> context.getString(R.string.notif_cooling)
            RoastPhase.IDLE                   -> context.getString(R.string.notif_monitoring)
        }
        val notification = buildRoastNotification(text)
        manager.notify(NOTIF_ID_MONITOR, notification)
    }

    fun fireSecondCrackAlarm() {
        val notification = NotificationCompat.Builder(context, CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.alert_sc_detected))
            .setContentText(context.getString(R.string.action_start_cooling))
            .setAutoCancel(true)
            .setContentIntent(mainActivityIntent())
            .build()
        manager.notify(NOTIF_ID_ALARM, notification)
    }
}
