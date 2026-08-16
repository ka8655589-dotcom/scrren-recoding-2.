package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.RecordingEntity
import com.example.data.SettingsManager
import com.example.drive.GoogleDriveUploader
import com.example.service.ScreenRecordService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val dao = db.recordingDao()
    val settingsManager = SettingsManager(application)
    private val uploader = GoogleDriveUploader(application)

    val recordingsList: StateFlow<List<RecordingEntity>> = dao.getAllRecordings()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val isRecording = ScreenRecordService.isRecording
    val totalDurationSeconds = ScreenRecordService.totalDurationSeconds
    val chunkDurationSeconds = ScreenRecordService.chunkDurationSeconds
    val currentBatteryLevel = ScreenRecordService.currentBatteryLevel
    val currentChunkIndex = ScreenRecordService.currentChunkIndex
    val activeTimeRangeTag = ScreenRecordService.activeTimeRangeTag
    val lastStopReason = ScreenRecordService.lastStopReason

    val batteryShieldEnabled = settingsManager.batteryShieldEnabledFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val batteryThreshold = settingsManager.batteryThresholdFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val splitDurationMins = settingsManager.splitDurationMinsFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 60)
    val autoUploadDrive = settingsManager.autoUploadDriveFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val recordAudio = settingsManager.recordAudioFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val driveConnected = settingsManager.driveConnectedFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val driveAccount = settingsManager.driveAccountFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "ka8655589@gmail.com")
    val driveFolder = settingsManager.driveFolderFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Screen_Recordings_24H")
    val driveOAuthToken = settingsManager.driveOAuthTokenFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val serviceAccountJson = settingsManager.serviceAccountJsonFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val videoResolution = settingsManager.videoResolutionFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "720p")
    val cameraOption = settingsManager.cameraOptionFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Screen Only")
    val autoDeleteAfterSync = settingsManager.autoDeleteAfterSyncFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val saveToGallery = settingsManager.saveToGalleryFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val s23StealthMode = settingsManager.s23StealthModeFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val autoStartOnBoot = settingsManager.autoStartOnBootFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun startRecording(context: Context) {
        try {
            val intent = Intent(context, ScreenRecordService::class.java).apply {
                action = ScreenRecordService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Toast.makeText(context, "24H Recording Started", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Could not start recording: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    fun stopRecording(context: Context) {
        try {
            val intent = Intent(context, ScreenRecordService::class.java).apply {
                action = ScreenRecordService.ACTION_STOP
            }
            context.startService(intent)
            Toast.makeText(context, "Recording Stopped and Saved", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun splitNow(context: Context) {
        val intent = Intent(context, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_SPLIT_NOW
        }
        context.startService(intent)
    }

    fun uploadRecordingToDrive(recording: RecordingEntity) {
        viewModelScope.launch {
            uploader.uploadRecording(recording)
        }
    }

    fun syncAllPendingToDrive() {
        viewModelScope.launch {
            uploader.syncPendingRecordings()
        }
    }

    fun deleteRecording(recording: RecordingEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteRecordingById(recording.id)
            try {
                val file = File(recording.filePath)
                if (file.exists()) {
                    file.delete()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun deleteAllRecordings(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteAll()
            try {
                val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                dir?.listFiles()?.forEach { file ->
                    if (file.name.startsWith("REC_") || file.name.endsWith(".mp4")) {
                        file.delete()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "All clips deleted from clip folder", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun updateBatteryShieldEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsManager.setBatteryShieldEnabled(enabled) }
    }

    fun updateBatteryThreshold(value: Int) {
        viewModelScope.launch { settingsManager.setBatteryThreshold(value) }
    }

    fun updateSplitDurationMins(value: Int) {
        viewModelScope.launch { settingsManager.setSplitDurationMins(value) }
    }

    fun updateAutoUploadDrive(value: Boolean) {
        viewModelScope.launch { settingsManager.setAutoUploadDrive(value) }
    }

    fun updateRecordAudio(value: Boolean) {
        viewModelScope.launch { settingsManager.setRecordAudio(value) }
    }

    fun updateDriveFolder(folder: String) {
        viewModelScope.launch { settingsManager.setDriveFolder(folder) }
    }

    fun updateDriveOAuthToken(token: String) {
        viewModelScope.launch { settingsManager.setDriveOAuthToken(token) }
    }

    fun updateServiceAccountJson(json: String) {
        viewModelScope.launch { settingsManager.setServiceAccountJson(json) }
    }

    fun testDriveConnection(token: String, folder: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val (success, message) = uploader.testDriveConnection(token, folder)
            if (success) {
                settingsManager.setDriveConnected(true)
            }
            withContext(Dispatchers.Main) {
                onResult(success, message)
            }
        }
    }

    fun updateDriveAccount(account: String) {
        viewModelScope.launch { settingsManager.setDriveAccount(account) }
    }

    fun toggleDriveConnected(connected: Boolean) {
        viewModelScope.launch { settingsManager.setDriveConnected(connected) }
    }

    fun updateVideoResolution(resolution: String) {
        viewModelScope.launch { settingsManager.setVideoResolution(resolution) }
    }

    fun updateCameraOption(option: String) {
        viewModelScope.launch { settingsManager.setCameraOption(option) }
    }

    fun updateAutoDeleteAfterSync(value: Boolean) {
        viewModelScope.launch { settingsManager.setAutoDeleteAfterSync(value) }
    }

    fun updateAutoStartOnBoot(value: Boolean) {
        viewModelScope.launch { settingsManager.setAutoStartOnBoot(value) }
    }

    fun updateSaveToGallery(value: Boolean) {
        viewModelScope.launch { settingsManager.setSaveToGallery(value) }
    }

    fun updateS23StealthMode(value: Boolean) {
        viewModelScope.launch { settingsManager.setS23StealthMode(value) }
    }

    fun openVideoInExternalPlayer(context: Context, recording: RecordingEntity) {
        val file = File(recording.filePath)
        if (!file.exists() || file.length() == 0L) {
            Toast.makeText(context, "Video file not found or is empty", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Play Video With"))
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Could not open external video player: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    fun shareVideo(context: Context, recording: RecordingEntity) {
        val file = File(recording.filePath)
        if (!file.exists()) {
            Toast.makeText(context, "Video file not found", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share Video Clip"))
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Error sharing video: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    fun saveVideoToGallery(context: Context, recording: RecordingEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val srcFile = File(recording.filePath)
            if (!srcFile.exists()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Local video file not found", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            try {
                val dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
                val cameraDir = File(dcimDir, "Camera")
                if (!cameraDir.exists()) cameraDir.mkdirs()

                val destFile = File(cameraDir, recording.fileName)
                srcFile.copyTo(destFile, overwrite = true)

                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(destFile.absolutePath),
                    arrayOf("video/mp4")
                ) { _, _ -> }

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Saved to Gallery! (DCIM/Camera/${recording.fileName})", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Exported to Gallery successfully", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun isBatteryOptimizationIgnored(context: Context): Boolean {
        return try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true
        } catch (e: Exception) {
            true
        }
    }

    fun requestIgnoreBatteryOptimizations(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
            } catch (e2: Exception) {
                Toast.makeText(context, "Battery settings opened in system Settings", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
