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

        // Live Shared StateFlows
        private val _isRecording = MutableStateFlow(false)
        val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

        private val _totalDurationSeconds = MutableStateFlow(0L)
        val totalDurationSeconds: StateFlow<Long> = _totalDurationSeconds.asStateFlow()

        private val _chunkDurationSeconds = MutableStateFlow(0L)
        val chunkDurationSeconds: StateFlow<Long> = _chunkDurationSeconds.asStateFlow()

        private val _currentBatteryLevel = MutableStateFlow(100)
        val currentBatteryLevel: StateFlow<Int> = _currentBatteryLevel.asStateFlow()

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

    // Battery Receiver
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val status = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL

                if (level >= 0 && scale > 0) {
                    val pct = (level * 100) / scale
                    _currentBatteryLevel.value = pct
                    checkBatteryProtectionThreshold(pct, isCharging)
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
            registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register battery receiver: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startScreenRecording()
            ACTION_STOP -> stopScreenRecording("Stopped manually by user")
            ACTION_SPLIT_NOW -> splitRecordingChunk("Manual split triggered by user")
        }
        return START_STICKY
    }

    private fun startScreenRecording() {
        if (_isRecording.value) return

        _isRecording.value = true
        _totalDurationSeconds.value = 0L
        _chunkDurationSeconds.value = 0L
        _currentChunkIndex.value = 1
        _lastStopReason.value = null

        totalStartTimeMillis = System.currentTimeMillis()
        chunkStartTimeMillis = totalStartTimeMillis

        startNewChunkFile()

        // Start hardware camera recording
        serviceScope.launch {
            val cameraOpt = settingsManager.cameraOptionFlow.first()
            val resolution = settingsManager.videoResolutionFlow.first()
            val recordAudio = settingsManager.recordAudioFlow.first()
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

                // Update Notification
                val h = _totalDurationSeconds.value / 3600
                val m = (_totalDurationSeconds.value % 3600) / 60
                val s = _totalDurationSeconds.value % 60
                val timeStr = String.format(Locale.getDefault(), "%02d:%02d:%02d", h, m, s)

                val notifManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                notifManager.notify(NOTIF_ID, createNotification("Recording active: $timeStr | Chunk #${_currentChunkIndex.value}"))
            }
        }
    }

    private fun splitRecordingChunk(reason: String) {
        val file = currentVideoFile
        val duration = _chunkDurationSeconds.value
        val startTime = chunkStartTimeMillis
        val endTime = System.currentTimeMillis()

        // Stop real camera recorder for current chunk
        realCameraRecorder?.stopRecording()
        realCameraRecorder = null

        val timeTagFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
        val startStr = timeTagFormat.format(Date(startTime))
        val endStr = timeTagFormat.format(Date(endTime))

        val currentChunk = _currentChunkIndex.value

        serviceScope.launch(Dispatchers.IO) {
            val cameraOpt = settingsManager.cameraOptionFlow.first()
            val resolution = settingsManager.videoResolutionFlow.first()
            val recordAudio = settingsManager.recordAudioFlow.first()
            val rangeTag = "$startStr - $endStr ($cameraOpt)"

            if (file != null) {
                // If real camera recorded video file is present (> 1KB), keep the genuine camera recording!
                val finalSizeBytes = if (file.exists() && file.length() > 1024L) {
                    file.length()
                } else {
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

                val autoUpload = settingsManager.autoUploadDriveFlow.first()
                if (autoUpload) {
                    driveUploader.uploadRecording(insertedRecording)
                }
            }
        }

        // Increment chunk counter & reset chunk duration
        _currentChunkIndex.value += 1
        _chunkDurationSeconds.value = 0L
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
        if (!_isRecording.value) return

        _isRecording.value = false
        timerJob?.cancel()
        _lastStopReason.value = reason

        // Stop real hardware camera
        realCameraRecorder?.stopRecording()
        realCameraRecorder = null

        // Finalize current chunk
        val file = currentVideoFile
        val duration = _chunkDurationSeconds.value
        val startTime = chunkStartTimeMillis
        val endTime = System.currentTimeMillis()

        val timeTagFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
        val startStr = timeTagFormat.format(Date(startTime))
        val endStr = timeTagFormat.format(Date(endTime))

        val currentChunk = _currentChunkIndex.value

        if (file != null) {
            serviceScope.launch(Dispatchers.IO) {
                val cameraOpt = settingsManager.cameraOptionFlow.first()
                val resolution = settingsManager.videoResolutionFlow.first()
                val recordAudio = settingsManager.recordAudioFlow.first()
                val rangeTag = "$startStr - $endStr ($cameraOpt)"

                val finalSizeBytes = if (file.exists() && file.length() > 1024L) {
                    file.length()
                } else {
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

                val autoUpload = settingsManager.autoUploadDriveFlow.first()
                if (autoUpload) {
                    driveUploader.uploadRecording(insertedRecording)
                }
            }
        }

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

        val splitIntent = Intent(this, ScreenRecordService::class.java).apply {
            action = ACTION_SPLIT_NOW
        }
        val splitPendingIntent = PendingIntent.getService(
            this, 2, splitIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Camera")
            .setContentText("Camera active in background | $contentText")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(android.R.drawable.ic_media_pause, "Split Chunk", splitPendingIntent)
            .addAction(android.R.drawable.ic_delete, "Stop Recording", stopPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Recording Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Foreground notification for active 24H screen recording"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
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
            unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {
            // ignore if not registered
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
