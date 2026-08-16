package com.example.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.data.SettingsManager
import com.example.service.ScreenRecordService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver that listens for device boot completion and automatically
 * restarts the 24/7 screen recording background service.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootCompletedReceiver"
        private const val BOOT_NOTIF_CHANNEL_ID = "boot_recording_channel"
        private const val BOOT_NOTIF_ID = 2001
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "Received boot broadcast with action: $action")

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            val pendingResult = goAsync()
            val settingsManager = SettingsManager(context.applicationContext)

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val autoStartOnBoot = settingsManager.autoStartOnBootFlow.first()
                    val wasRecording = settingsManager.wasRecordingFlow.first()

                    Log.i(TAG, "Device rebooted. autoStartOnBoot: $autoStartOnBoot, wasRecording: $wasRecording")

                    if (autoStartOnBoot || wasRecording) {
                        // Start ScreenRecordService in foreground
                        val serviceIntent = Intent(context, ScreenRecordService::class.java).apply {
                            this.action = ScreenRecordService.ACTION_START
                        }

                        try {
                            ContextCompat.startForegroundService(context, serviceIntent)
                            Log.i(TAG, "ScreenRecordService successfully started on boot")
                        } catch (e: Exception) {
                            Log.e(TAG, "Direct startForegroundService on boot exception: ${e.message}")
                        }

                        // Post high-priority notification to inform user and ensure quick resume
                        notifyUserOfBootRestart(context)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling boot broadcast: ${e.message}", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    private fun notifyUserOfBootRestart(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                BOOT_NOTIF_CHANNEL_ID,
                "Device Reboot Recording Auto-Start",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifies when background recording automatically restarts after reboot"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, BOOT_NOTIF_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("24H Recorder • Device Rebooted")
            .setContentText("Background recording automatically resumed after device restart.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(BOOT_NOTIF_ID, notification)
    }
}
