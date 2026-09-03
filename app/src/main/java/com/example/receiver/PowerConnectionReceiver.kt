package com.example.receiver

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.data.SettingsManager
import com.example.service.ChargingJobService
import com.example.service.ScreenRecordService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver that responds to ACTION_POWER_CONNECTED to start
 * recording automatically whenever charger is connected without showing intrusive notifications.
 */
class PowerConnectionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PowerConnectionReceiver"
        private const val OLD_CHARGING_NOTIF_CHANNEL_ID = "charging_auto_start_channel"
        private const val OLD_CHARGING_NOTIF_ID = 3001
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "Power broadcast received: $action")

        // Dismiss any old notification to ensure zero notification clutter
        clearOldNotifications(context)

        if (action == Intent.ACTION_POWER_CONNECTED) {
            val pendingResult = goAsync()
            val settingsManager = SettingsManager(context.applicationContext)

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val autoStart = settingsManager.autoStartOnChargingFlow.first()
                    Log.i(TAG, "Power connected. autoStartOnCharging=$autoStart, isRecording=${ScreenRecordService.isRecording.value}")

                    if (autoStart && !ScreenRecordService.isRecording.value) {
                        val serviceIntent = Intent(context, ScreenRecordService::class.java).apply {
                            this.action = ScreenRecordService.ACTION_START
                        }

                        try {
                            ContextCompat.startForegroundService(context, serviceIntent)
                            Log.i(TAG, "ScreenRecordService started silently on power connect")
                        } catch (e: Exception) {
                            Log.e(TAG, "startForegroundService failed: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in PowerConnectionReceiver: ${e.message}", e)
                } finally {
                    // Re-assert JobScheduler job for reliability across reboots
                    ChargingJobService.scheduleChargingJob(context)
                    pendingResult.finish()
                }
            }
        }
    }

    private fun clearOldNotifications(context: Context) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            notificationManager.cancel(OLD_CHARGING_NOTIF_ID)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                notificationManager.deleteNotificationChannel(OLD_CHARGING_NOTIF_CHANNEL_ID)
            }
        } catch (e: Exception) {
            // ignore cleanup errors
        }
    }
}

