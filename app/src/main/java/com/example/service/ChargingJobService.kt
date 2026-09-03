package com.example.service

import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.data.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * System JobService triggered by Android OS when the device starts charging.
 * Guarantees background wake-up and auto-start on Android 8.0+ when plugged in silently.
 */
class ChargingJobService : JobService() {

    companion object {
        private const val TAG = "ChargingJobService"
        const val CHARGING_JOB_ID = 4001
        private const val OLD_CHARGING_NOTIF_CHANNEL_ID = "charging_auto_start_channel"
        private const val OLD_CHARGING_NOTIF_ID = 3002

        fun scheduleChargingJob(context: Context) {
            try {
                val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
                val component = ComponentName(context, ChargingJobService::class.java)
                val builder = JobInfo.Builder(CHARGING_JOB_ID, component)
                    .setRequiresCharging(true)
                    .setPersisted(true)

                val result = scheduler.schedule(builder.build())
                Log.d(TAG, "Scheduled charging job (result: $result)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule charging job: ${e.message}")
            }
        }

        fun cancelChargingJob(context: Context) {
            try {
                val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
                scheduler.cancel(CHARGING_JOB_ID)
                Log.d(TAG, "Cancelled charging job")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cancel charging job: ${e.message}")
            }
        }
    }

    override fun onStartJob(params: JobParameters?): Boolean {
        Log.i(TAG, "ChargingJobService triggered by Android OS: Device is charging!")
        val settingsManager = SettingsManager(applicationContext)

        clearOldNotifications(applicationContext)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val autoStart = settingsManager.autoStartOnChargingFlow.first()
                Log.i(TAG, "Charging detected by JobScheduler. autoStartOnCharging=$autoStart, isRecording=${ScreenRecordService.isRecording.value}")

                if (autoStart && !ScreenRecordService.isRecording.value) {
                    val serviceIntent = Intent(applicationContext, ScreenRecordService::class.java).apply {
                        action = ScreenRecordService.ACTION_START
                    }
                    try {
                        ContextCompat.startForegroundService(applicationContext, serviceIntent)
                        Log.i(TAG, "ScreenRecordService started silently by ChargingJobService")
                    } catch (e: Exception) {
                        Log.e(TAG, "startForegroundService exception: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in ChargingJobService: ${e.message}", e)
            } finally {
                // Keep job scheduled for subsequent unplug & plug-in cycles
                scheduleChargingJob(applicationContext)
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        // Reschedule if job was interrupted before completion
        return true
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

