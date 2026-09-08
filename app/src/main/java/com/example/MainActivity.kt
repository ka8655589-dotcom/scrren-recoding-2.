package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.service.ChargingJobService
import com.example.service.ScreenRecordService
import com.example.ui.AppNavigation
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.MainViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private var lastVolumeDownTime = 0L

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Permissions granted status handled
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        checkAndRequestPermissions()

        // Schedule charging detection job in system JobScheduler
        ChargingJobService.scheduleChargingJob(this)

        // Ensure Smart Screen On/Off auto-recording trigger service is armed and active
        viewModel.ensureScreenTriggerServiceRunning(this)

        // Handle double-press side key camera / quick launch intent
        handleIncomingTriggerIntent(intent)

        // If app is opened while plugged in to charger, auto-start if configured
        lifecycleScope.launch {
            try {
                val autoStartCharging = viewModel.settingsManager.autoStartOnChargingFlow.first()
                if (autoStartCharging && !ScreenRecordService.isRecording.value) {
                    val batteryStatus = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                    val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                    if (isCharging) {
                        viewModel.startRecording(this@MainActivity)
                    }
                }
            } catch (e: Exception) {
                // Ignore initialization check error
            }
        }

        setContent {
            MyApplicationTheme {
                AppNavigation(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingTriggerIntent(intent)
    }

    private fun handleIncomingTriggerIntent(incomingIntent: Intent?) {
        val action = incomingIntent?.action ?: return
        val isCameraOrQuickLaunch = action == MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA ||
                action == MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE ||
                action == MediaStore.INTENT_ACTION_VIDEO_CAMERA ||
                action == MediaStore.ACTION_IMAGE_CAPTURE ||
                action == MediaStore.ACTION_VIDEO_CAPTURE

        if (isCameraOrQuickLaunch) {
            lifecycleScope.launch {
                vibrateFeedback()
                if (!ScreenRecordService.isRecording.value) {
                    viewModel.startRecording(this@MainActivity)
                    Toast.makeText(this@MainActivity, "Double Side Key Quick Launch: Recording Started!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Physical Double-click Volume Down shortcut to start/stop recording quickly
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val now = System.currentTimeMillis()
            if (now - lastVolumeDownTime < 500) {
                vibrateFeedback()
                if (ScreenRecordService.isRecording.value || ScreenRecordService.isScreenStandby.value) {
                    viewModel.stopRecording(this)
                    Toast.makeText(this, "Double Button Press: Recording Stopped", Toast.LENGTH_SHORT).show()
                } else {
                    viewModel.startRecording(this)
                    Toast.makeText(this, "Double Button Press: Recording Started", Toast.LENGTH_SHORT).show()
                }
                lastVolumeDownTime = 0L
                return true
            }
            lastVolumeDownTime = now
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun vibrateFeedback() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(
                    VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.vibrate(
                    VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.CAMERA)
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }
}

