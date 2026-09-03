package com.example

import android.app.job.JobScheduler
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.data.SettingsManager
import com.example.receiver.PowerConnectionReceiver
import com.example.service.ChargingJobService
import com.example.service.ScreenRecordService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChargingAutoStartTest {

    private lateinit var context: Context
    private lateinit var settingsManager: SettingsManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        settingsManager = SettingsManager(context)
    }

    @Test
    fun `test autoStartOnCharging defaults to true`() = runBlocking {
        val autoStart = settingsManager.autoStartOnChargingFlow.first()
        assertTrue("Auto-start on charging should default to true", autoStart)
    }

    @Test
    fun `test scheduleChargingJob registers job in JobScheduler with requiresCharging`() {
        ChargingJobService.scheduleChargingJob(context)

        val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        val pendingJob = jobScheduler.getPendingJob(ChargingJobService.CHARGING_JOB_ID)

        assertNotNull("JobScheduler should contain the scheduled charging job", pendingJob)
        assertEquals("Job ID should match CHARGING_JOB_ID", ChargingJobService.CHARGING_JOB_ID, pendingJob?.id)
        assertTrue("Job must require charging constraint", pendingJob?.isRequireCharging == true)
    }

    @Test
    fun `test PowerConnectionReceiver starts ScreenRecordService when charger is connected`() = runBlocking {
        settingsManager.setAutoStartOnCharging(true)

        val receiver = PowerConnectionReceiver()
        val intent = Intent(Intent.ACTION_POWER_CONNECTED)
        receiver.onReceive(context, intent)

        // Give coroutine a moment to complete
        Thread.sleep(200)

        val shadowApp = shadowOf(context as android.app.Application)
        val nextStartedService = shadowApp.nextStartedService

        assertNotNull("ScreenRecordService should be started on power connect", nextStartedService)
        assertEquals(ScreenRecordService.ACTION_START, nextStartedService?.action)
    }

    @Test
    fun `test PowerConnectionReceiver does not start service when autoStartOnCharging is disabled`() = runBlocking {
        settingsManager.setAutoStartOnCharging(false)

        val shadowApp = shadowOf(context as android.app.Application)
        while (shadowApp.nextStartedService != null) {
            // drain any previously queued service starts
        }

        val receiver = PowerConnectionReceiver()
        val intent = Intent(Intent.ACTION_POWER_CONNECTED)
        receiver.onReceive(context, intent)

        Thread.sleep(200)

        val nextStartedService = shadowApp.nextStartedService
        org.junit.Assert.assertNull("ScreenRecordService should NOT start when disabled by user", nextStartedService)
    }

    @Test
    fun `test no popup notification is posted when charger is connected`() = runBlocking {
        settingsManager.setAutoStartOnCharging(true)

        val receiver = PowerConnectionReceiver()
        val intent = Intent(Intent.ACTION_POWER_CONNECTED)
        receiver.onReceive(context, intent)

        Thread.sleep(200)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val shadowNotificationManager = shadowOf(notificationManager)
        assertEquals("Zero popup notifications should be posted when charger is connected", 0, shadowNotificationManager.allNotifications.size)
    }
}
