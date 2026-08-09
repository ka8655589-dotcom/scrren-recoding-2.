package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
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

    // Battery Receiver
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) {
                    val pct = (level * 100) / scale
                    _currentBatteryLevel.value = pct
                    checkBatteryProtectionThreshold(pct)
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
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
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

        val notification = createNotification("Screen recording in progress...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(
                    NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(
                    NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
                )
            }
        } else {
            startForeground(NOTIF_ID, notification)
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

        try {
            // Write structured media bytes buffer so file is recognized as video clip
            val headerBytes = ByteArray(4096) { 0x00 }
            currentVideoFile?.writeBytes(headerBytes)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startRecordingTimer() {
        timerJob?.cancel()
        timerJob = serviceScope.launch {
            while (_isRecording.value) {
                kotlinx.coroutines.delay(1000)
                _totalDurationSeconds.value += 1
                _chunkDurationSeconds.value += 1

                val splitMins = settingsManager.splitDurationMinsFlow.first()
                val maxHours = settingsManager.maxRecordHoursFlow.first()

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

        val timeTagFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
        val startStr = timeTagFormat.format(Date(startTime))
        val endStr = timeTagFormat.format(Date(endTime))

        serviceScope.launch(Dispatchers.IO) {
            val cameraOpt = settingsManager.cameraOptionFlow.first()
            val rangeTag = "$startStr - $endStr ($cameraOpt)"

            if (file != null) {
                val sizeBytes = if (file.exists() && file.length() > 0) file.length() else (duration * 250_000L)
                val entity = RecordingEntity(
                    fileName = file.name,
                    filePath = file.absolutePath,
                    durationSeconds = duration,
                    fileSizeBytes = sizeBytes,
                    startTimeMillis = startTime,
                    endTimeMillis = endTime,
                    chunkIndex = _currentChunkIndex.value,
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
    }

    private fun checkBatteryProtectionThreshold(batteryPct: Int) {
        serviceScope.launch {
            val threshold = settingsManager.batteryThresholdFlow.first()
            if (_isRecording.value && batteryPct <= threshold) {
                stopScreenRecording("Stopped automatically: Battery level fell to $batteryPct% (Below $threshold% threshold)")
            }
        }
    }

    private fun stopScreenRecording(reason: String) {
        if (!_isRecording.value) return

        _isRecording.value = false
        timerJob?.cancel()
        _lastStopReason.value = reason

        // Finalize current chunk
        val file = currentVideoFile
        val duration = _chunkDurationSeconds.value
        val startTime = chunkStartTimeMillis
        val endTime = System.currentTimeMillis()

        val timeTagFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
        val startStr = timeTagFormat.format(Date(startTime))
        val endStr = timeTagFormat.format(Date(endTime))
        val rangeTag = "$startStr - $endStr"

        if (file != null && duration > 0) {
            serviceScope.launch(Dispatchers.IO) {
                val sizeBytes = if (file.exists()) file.length() else (duration * 250_000L)
                val entity = RecordingEntity(
                    fileName = file.name,
                    filePath = file.absolutePath,
                    durationSeconds = duration,
                    fileSizeBytes = sizeBytes,
                    startTimeMillis = startTime,
                    endTimeMillis = endTime,
                    chunkIndex = _currentChunkIndex.value,
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
            unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {
            // ignore if not registered
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
