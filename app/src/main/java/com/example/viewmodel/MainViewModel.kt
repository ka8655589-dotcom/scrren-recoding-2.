package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
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

    val batteryThreshold = settingsManager.batteryThresholdFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 20)
    val splitDurationMins = settingsManager.splitDurationMinsFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 60)
    val autoUploadDrive = settingsManager.autoUploadDriveFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val recordAudio = settingsManager.recordAudioFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val driveConnected = settingsManager.driveConnectedFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val driveAccount = settingsManager.driveAccountFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "ka8655589@gmail.com")
    val driveFolder = settingsManager.driveFolderFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Screen_Recordings_24H")
    val videoResolution = settingsManager.videoResolutionFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "720p")
    val cameraOption = settingsManager.cameraOptionFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Screen Only")
    val autoDeleteAfterSync = settingsManager.autoDeleteAfterSyncFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val saveToGallery = settingsManager.saveToGalleryFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val s23StealthMode = settingsManager.s23StealthModeFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun startRecording(context: Context) {
        val intent = Intent(context, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stopRecording(context: Context) {
        val intent = Intent(context, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_STOP
        }
        context.startService(intent)
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

    fun updateSaveToGallery(value: Boolean) {
        viewModelScope.launch { settingsManager.setSaveToGallery(value) }
    }

    fun updateS23StealthMode(value: Boolean) {
        viewModelScope.launch { settingsManager.setS23StealthMode(value) }
    }

    fun saveVideoToGallery(context: Context, recording: RecordingEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val srcFile = File(recording.filePath)
            if (!srcFile.exists()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Local video file was auto-cleaned after Drive sync", Toast.LENGTH_SHORT).show()
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
}
