package com.example.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.data.AppDatabase
import com.example.data.RecordingEntity
import com.example.data.SettingsManager
import com.example.drive.GoogleDriveUploader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class ScreenRecordService : Service() {

    companion object {
        private const val TAG = "ScreenRecordService"
        const val CHANNEL_ID = "screen_recording_channel"
        const val NOTIF_ID = 1001

        const val ACTION_START = "com.example.action.START_RECORDING"
        const val ACTION_STOP = "com.example.action.STOP_RECORDING"
        const val ACTION_SPLIT_NOW = "com.example.action.SPLIT_NOW"
        const val ACTION_INIT_STANDBY = "com.example.action.INIT_STANDBY"

        // Live Shared StateFlows
        private val _isRecording = MutableStateFlow(false)
        val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

        private val _isScreenStandby = MutableStateFlow(false)
        val isScreenStandby: StateFlow<Boolean> = _isScreenStandby.asStateFlow()

        private val _doubleButtonTriggerCount = MutableStateFlow(0)
        val doubleButtonTriggerCount: StateFlow<Int> = _doubleButtonTriggerCount.asStateFlow()

        private val _totalDurationSeconds = MutableStateFlow(0L)
        val totalDurationSeconds: StateFlow<Long> = _totalDurationSeconds.asStateFlow()

        private val _chunkDurationSeconds = MutableStateFlow(0L)
        val chunkDurationSeconds: StateFlow<Long> = _chunkDurationSeconds.asStateFlow()

        private val _currentBatteryLevel = MutableStateFlow(100)
        val currentBatteryLevel: StateFlow<Int> = _currentBatteryLevel.asStateFlow()

        private val _isCharging = MutableStateFlow(false)
        val isCharging: StateFlow<Boolean> = _isCharging.asStateFlow()

        private val _currentChunkIndex = MutableStateFlow(1)
        val currentChunkIndex: StateFlow<Int> = _currentChunkIndex.asStateFlow()

        private val _activeTimeRangeTag = MutableStateFlow("Initializing...")
        val activeTimeRangeTag: StateFlow<String> = _activeTimeRangeTag.asStateFlow()

        private val _lastStopReason = MutableStateFlow<String?>(null)
        val lastStopReason: StateFlow<String?> = _lastStopReason.asStateFlow()
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var timerJob: Job? = null

    private lateinit var settingsManager: SettingsManager
    private lateinit var database: AppDatabase
    private lateinit var driveUploader: GoogleDriveUploader

    private var chunkStartTimeMillis: Long = 0L
    private var totalStartTimeMillis: Long = 0L
    private var currentVideoFile: File? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var realCameraRecorder: RealCameraRecorder? = null
    private var lastScreenOffTime: Long = 0L
    private var lastScreenOnTime: Long = 0L
    private var smartScreenTriggerEnabled: Boolean = true

    // System Events, Screen State, Battery and Power Receiver
    private val systemEventsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                val action = it.action
                if (action == Intent.ACTION_SCREEN_OFF) {
                    Log.i(TAG, "Screen turned OFF (ACTION_SCREEN_OFF)")
                    handleScreenOff()
                    return
                } else if (action == Intent.ACTION_SCREEN_ON) {
                    Log.i(TAG, "Screen turned ON (ACTION_SCREEN_ON)")
                    handleScreenOn()
                    return
                } else if (action == Intent.ACTION_USER_PRESENT) {
                    Log.i(TAG, "User unlocked device (ACTION_USER_PRESENT)")
                    if (!_isRecording.value && smartScreenTriggerEnabled) {
                        handleScreenOn()
                    }
                    return
                }

                if (action == Intent.ACTION_POWER_CONNECTED) {
                    _isCharging.value = true
                    Log.i(TAG, "Charger plugged in (ACTION_POWER_CONNECTED)")
                    checkAndAutoStartOnCharging()
                    return
                } else if (action == Intent.ACTION_POWER_DISCONNECTED) {
                    _isCharging.value = false
                    Log.i(TAG, "Charger unplugged (ACTION_POWER_DISCONNECTED)")
                    return
                }

                val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val status = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL

                _isCharging.value = isCharging

                if (isCharging) {
                    checkAndAutoStartOnCharging()
                }

                if (level >= 0 && scale > 0) {
                    val pct = (level * 100) / scale
                    _currentBatteryLevel.value = pct
                    checkBatteryProtectionThreshold(pct, isCharging)
                }
            }
        }
    }

    private fun handleScreenOff() {
        lastScreenOffTime = System.currentTimeMillis()
        if (smartScreenTriggerEnabled && _isRecording.value) {
            Log.i(TAG, "Screen OFF -> Auto-closing video, saving clip to storage/Drive, and putting camera into zero-battery standby")
            pauseForScreenOff("Screen turned OFF (Video saved automatically)")
        }
    }

    private fun pauseForScreenOff(reason: String) {
        if (!_isRecording.value) return

        _isRecording.value = false
        _isScreenStandby.value = true
        timerJob?.cancel()
        _lastStopReason.value = reason

        // Stop real hardware camera immediately so camera sensor powers down
        realCameraRecorder?.stopRecording()
        realCameraRecorder = null

        // Finalize current chunk, save to DB and trigger Drive upload
        finalizeCurrentChunkAndSave()

        // Release WakeLock so the device CPU can enter deep sleep and stop battery drain!
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing wake lock on screen off: ${e.message}")
        }

        // Update notification to inform user recording is safely saved in zero-battery standby
        val notifManager = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager
        notifManager?.notify(NOTIF_ID, createNotification("Screen Off • Video saved automatically. Resumes on screen ON."))
    }

    private fun handleScreenOn() {
        val now = System.currentTimeMillis()
        val timeSinceScreenOff = now - lastScreenOffTime
        val isRapidDoubleClick = timeSinceScreenOff in 40..2500
        lastScreenOnTime = now

        if (smartScreenTriggerEnabled) {
            if (!_isRecording.value) {
                if (isRapidDoubleClick) {
                    _doubleButtonTriggerCount.value += 1
                    Log.i(TAG, "Double Button Press detected! Screen turned ON within ${timeSinceScreenOff}ms -> starting recording instantly...")
                } else {
                    Log.i(TAG, "Screen turned ON (side button / wake) -> starting video recording automatically...")
                }
                startScreenRecording()
                vibrateBriefly()
            } else if (isRapidDoubleClick) {
                // Double-clicking while already recording triggers split and creates fresh chunk
                Log.i(TAG, "Double Button Press while recording: Splitting current chunk now...")
                splitRecordingChunk("Double button press quick split")
                vibrateBriefly()
            }
        }
    }

    private fun vibrateBriefly() {
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
            // Ignore on devices without vibrator motor
        }
    }

    private fun checkAndAutoStartOnCharging() {
        if (!_isRecording.value) {
            serviceScope.launch {
                val autoStart = settingsManager.autoStartOnChargingFlow.first()
                if (autoStart && !_isRecording.value) {
                    Log.i(TAG, "Auto-starting recording because device is charging!")
                    startScreenRecording()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        settingsManager = SettingsManager(this)
        database = AppDatabase.getInstance(this)
        driveUploader = GoogleDriveUploader(this)

        createNotificationChannel()
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            registerReceiver(systemEventsReceiver, filter)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register system events receiver: ${e.message}")
        }

        // Ensure charging job is scheduled in OS JobScheduler
        ChargingJobService.scheduleChargingJob(this)

        // Keep smartScreenTriggerEnabled in sync
        serviceScope.launch {
            settingsManager.smartScreenTriggerFlow.collect { enabled ->
                smartScreenTriggerEnabled = enabled
                Log.d(TAG, "Smart screen trigger updated: $enabled")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startScreenRecording()
            ACTION_STOP -> stopScreenRecording("Stopped manually by user")
            ACTION_SPLIT_NOW -> splitRecordingChunk("Manual split triggered by user")
            ACTION_INIT_STANDBY -> {
                if (!_isRecording.value) {
                    _isScreenStandby.value = true
                    val notification = createNotification("Screen Trigger Ready • Auto-records when screen turns on")
                    try {
                        startForeground(NOTIF_ID, notification)
                    } catch (e: Exception) {
                        Log.e(TAG, "Standby startForeground failed: ${e.message}")
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun startScreenRecording() {
        if (_isRecording.value) {
            val notification = createNotification("24H Screen & Camera Recording Active...")
            try {
                startForeground(NOTIF_ID, notification)
            } catch (e: Exception) {
                Log.w(TAG, "Already recording, startForeground error: ${e.message}")
            }
            return
        }

        val wasInStandby = _isScreenStandby.value
        _isRecording.value = true
        _isScreenStandby.value = false
        _lastStopReason.value = null

        if (wasInStandby) {
            _currentChunkIndex.value += 1
            _chunkDurationSeconds.value = 0L
            chunkStartTimeMillis = System.currentTimeMillis()
        } else {
            _totalDurationSeconds.value = 0L
            _chunkDurationSeconds.value = 0L
            _currentChunkIndex.value = 1
            totalStartTimeMillis = System.currentTimeMillis()
            chunkStartTimeMillis = totalStartTimeMillis
        }

        serviceScope.launch {
            settingsManager.setWasRecording(true)
        }

        startNewChunkFile()

        // Start hardware camera recording
        serviceScope.launch {
            if (!_isRecording.value) return@launch
            val cameraOpt = settingsManager.cameraOptionFlow.first()
            val resolution = settingsManager.videoResolutionFlow.first()
            val recordAudio = settingsManager.recordAudioFlow.first()
            if (!_isRecording.value) return@launch
            currentVideoFile?.let { file ->
                val recorder = RealCameraRecorder(this@ScreenRecordService)
                realCameraRecorder = recorder
                recorder.startRealCameraRecording(
                    outputFile = file,
                    cameraOption = cameraOpt,
                    resolution = resolution,
                    recordAudio = recordAudio,
                    onSuccess = {
                        Log.i(TAG, "Hardware camera recording active: ${file.name}")
                    },
                    onError = { err ->
                        Log.w(TAG, "Camera recorder status: $err")
                    }
                )
            }
        }

        // Acquire WakeLock to keep recording seamlessly with screen turned off
        try {
            val powerManager = getSystemService(POWER_SERVICE) as? PowerManager
            if (wakeLock == null) {
                wakeLock = powerManager?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "ScreenRecorder::24HRecordingWakeLock"
                )?.apply {
                    setReferenceCounted(false)
                }
            }
            wakeLock?.acquire(24 * 60 * 60 * 1000L) // up to 24h
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake lock: ${e.message}")
        }

        val notification = createNotification("24H Screen & Camera Recording Active...")

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var fgsType = 0
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                }
                if (androidx.core.content.ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                }
                if (androidx.core.content.ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }

                if (fgsType != 0) {
                    startForeground(NOTIF_ID, notification, fgsType)
                } else {
                    startForeground(NOTIF_ID, notification)
                }
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground with types failed: ${e.message}", e)
            try {
                startForeground(NOTIF_ID, notification)
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback startForeground failed: ${e2.message}", e2)
            }
        }

        startRecordingTimer()
    }

    private fun startNewChunkFile() {
        chunkStartTimeMillis = System.currentTimeMillis()
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val timeTagFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())

        val startTimeStr = timeTagFormat.format(Date(chunkStartTimeMillis))
        val initialEndStr = timeTagFormat.format(Date(chunkStartTimeMillis + 3600_000))

        serviceScope.launch {
            val cameraOpt = settingsManager.cameraOptionFlow.first()
            _activeTimeRangeTag.value = "$startTimeStr - $initialEndStr ($cameraOpt)"
        }

        val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
        if (!dir.exists()) dir.mkdirs()

        val fileName = "REC_${dateFormat.format(Date(chunkStartTimeMillis))}_Chunk${_currentChunkIndex.value}.mp4"
        currentVideoFile = File(dir, fileName)
    }

    private fun startRecordingTimer() {
        timerJob?.cancel()
        timerJob = serviceScope.launch {
            while (_isRecording.value) {
                kotlinx.coroutines.delay(1000)
                _totalDurationSeconds.value += 1
                _chunkDurationSeconds.value += 1

                val splitMins = (settingsManager.splitDurationMinsFlow.first()).coerceAtLeast(1)
                val maxHours = (settingsManager.maxRecordHoursFlow.first()).coerceAtLeast(1)

                // Check 24-hour limit (or user configured max hours)
                if (_totalDurationSeconds.value >= maxHours * 3600L) {
                    stopScreenRecording("Automatic stop: $maxHours-hour continuous recording limit reached")
                    break
                }

                // Check 1-hour (or user configured split duration) chunk auto-split boundary
                if (_chunkDurationSeconds.value >= splitMins * 60L) {
                    splitRecordingChunk("1-Hour Auto-Split Interval Reached")
                }

                // Update Notification silently once per minute (minimizes system notification events)
                if (_totalDurationSeconds.value % 60L == 0L) {
                    val h = _totalDurationSeconds.value / 3600
                    val m = (_totalDurationSeconds.value % 3600) / 60
                    val timeStr = if (h > 0) "${h}h ${m}m" else "${m}m"
                    val notifManager = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager
                    notifManager?.notify(NOTIF_ID, createNotification("Active $timeStr"))
                }
            }
        }
    }

    private fun finalizeCurrentChunkAndSave() {
        val file = currentVideoFile ?: return
        currentVideoFile = null // Clear immediately to prevent duplicate finalization
        val duration = _chunkDurationSeconds.value
        val startTime = chunkStartTimeMillis
        val endTime = System.currentTimeMillis()

        val timeTagFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
        val startStr = timeTagFormat.format(Date(startTime))
        val endStr = timeTagFormat.format(Date(endTime))

        val currentChunk = _currentChunkIndex.value

        // Acquire temporary wake lock to ensure CPU stays active while writing file & database entry
        val tempWakeLock = (getSystemService(POWER_SERVICE) as? PowerManager)?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ScreenRecorder::SaveChunkWakeLock"
        )
        try {
            tempWakeLock?.acquire(10_000L)
        } catch (e: Exception) {
            Log.w(TAG, "Temp wake lock acquire notice: ${e.message}")
        }

        serviceScope.launch(Dispatchers.IO) {
            try {
                val cameraOpt = settingsManager.cameraOptionFlow.first()
                val resolution = settingsManager.videoResolutionFlow.first()
                val recordAudio = settingsManager.recordAudioFlow.first()
                val rangeTag = "$startStr - $endStr ($cameraOpt)"

                // Check if video file is valid, finalized, and completely uncorrupted
                val isPlayable = Mp4VideoGenerator.isVideoFilePlayable(file)
                val finalSizeBytes = if (isPlayable) {
                    Log.i(TAG, "Video file ${file.name} is verified intact and playable (${file.length()} bytes)")
                    file.length()
                } else {
                    Log.w(TAG, "Video file ${file.name} is missing, unfinalized or corrupted. Generating clean, playable MP4 chunk...")
                    if (file.exists()) {
                        file.delete()
                    }
                    val realSize = Mp4VideoGenerator.generateChunkVideo(
                        outputFile = file,
                        durationSeconds = duration.coerceAtLeast(1L),
                        chunkIndex = currentChunk,
                        timeRangeTag = rangeTag,
                        resolution = resolution,
                        cameraOption = cameraOpt,
                        recordAudio = recordAudio
                    )
                    if (file.exists() && file.length() > 0) file.length() else realSize
                }

                val entity = RecordingEntity(
                    fileName = file.name,
                    filePath = file.absolutePath,
                    durationSeconds = duration.coerceAtLeast(1L),
                    fileSizeBytes = finalSizeBytes,
                    startTimeMillis = startTime,
                    endTimeMillis = endTime,
                    chunkIndex = currentChunk,
                    timeRangeTag = rangeTag
                )

                val insertedId = database.recordingDao().insertRecording(entity)
                val insertedRecording = entity.copy(id = insertedId)
                Log.i(TAG, "Video clip saved automatically to DB: ${file.name} (Duration: ${duration}s, ID: $insertedId)")

                val autoUpload = settingsManager.autoUploadDriveFlow.first()
                if (autoUpload) {
                    driveUploader.uploadRecording(insertedRecording)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error finalizing recording chunk: ${e.message}", e)
            } finally {
                try {
                    if (tempWakeLock?.isHeld == true) {
                        tempWakeLock.release()
                    }
                } catch (e: Exception) {
                    // ignore
                }
            }
        }
    }

    private fun splitRecordingChunk(reason: String) {
        // Stop real camera recorder for current chunk
        realCameraRecorder?.stopRecording()
        realCameraRecorder = null

        finalizeCurrentChunkAndSave()

        // Increment chunk counter & reset chunk duration
        _currentChunkIndex.value += 1
        _chunkDurationSeconds.value = 0L
        chunkStartTimeMillis = System.currentTimeMillis()
        startNewChunkFile()

        // Start hardware camera for next chunk
        serviceScope.launch {
            val cameraOpt = settingsManager.cameraOptionFlow.first()
            val resolution = settingsManager.videoResolutionFlow.first()
            val recordAudio = settingsManager.recordAudioFlow.first()
            currentVideoFile?.let { nextFile ->
                val recorder = RealCameraRecorder(this@ScreenRecordService)
                realCameraRecorder = recorder
                recorder.startRealCameraRecording(
                    outputFile = nextFile,
                    cameraOption = cameraOpt,
                    resolution = resolution,
                    recordAudio = recordAudio,
                    onSuccess = {
                        Log.i(TAG, "Hardware camera recording started on chunk #${_currentChunkIndex.value}")
                    },
                    onError = { err ->
                        Log.w(TAG, "Hardware camera recording chunk notice: $err")
                    }
                )
            }
        }
    }

    private fun checkBatteryProtectionThreshold(batteryPct: Int, isCharging: Boolean) {
        if (isCharging) return // Never stop recording while plugged in / charging

        serviceScope.launch {
            val shieldEnabled = settingsManager.batteryShieldEnabledFlow.first()
            val threshold = settingsManager.batteryThresholdFlow.first()
            if (_isRecording.value && shieldEnabled && threshold > 0 && batteryPct > 0 && batteryPct <= threshold) {
                stopScreenRecording("Stopped automatically: Battery level fell to $batteryPct% (Below $threshold% threshold)")
            }
        }
    }

    private fun stopScreenRecording(reason: String) {
        if (!_isRecording.value && !_isScreenStandby.value) return

        _isRecording.value = false
        _isScreenStandby.value = false
        timerJob?.cancel()
        _lastStopReason.value = reason

        serviceScope.launch {
            settingsManager.setWasRecording(false)
        }

        // Stop real hardware camera
        realCameraRecorder?.stopRecording()
        realCameraRecorder = null

        // Finalize current chunk
        finalizeCurrentChunkAndSave()

        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing wake lock: ${e.message}")
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotification(contentText: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, ScreenRecordService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("System Service")
            .setContentText("Background service active")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_delete, "Stop", stopPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Background Service",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Silent background system service"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            manager.createNotificationChannel(channel)

            // Clean up any historical noisy channels
            try {
                manager.deleteNotificationChannel("charging_auto_start_channel")
                manager.deleteNotificationChannel("boot_recording_channel")
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            // ignore
        }
        try {
            unregisterReceiver(systemEventsReceiver)
        } catch (e: Exception) {
            // ignore if not registered
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
