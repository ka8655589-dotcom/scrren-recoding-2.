package com.example.drive

import android.content.Context
import com.example.data.AppDatabase
import com.example.data.RecordingEntity
import com.example.data.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class GoogleDriveUploader(private val context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val dao = db.recordingDao()
    private val settingsManager = SettingsManager(context)

    suspend fun uploadRecording(recording: RecordingEntity): Boolean = withContext(Dispatchers.IO) {
        val file = File(recording.filePath)
        if (!file.exists()) {
            dao.updateRecording(
                recording.copy(
                    uploadStatus = "FAILED",
                    errorMessage = "Local video file not found"
                )
            )
            return@withContext false
        }

        // Set status to UPLOADING
        dao.updateRecording(
            recording.copy(
                uploadStatus = "UPLOADING",
                errorMessage = null
            )
        )

        try {
            // Simulate chunked upload progress
            val totalSteps = 5
            for (step in 1..totalSteps) {
                delay(400) // Simulate network transfer time
            }

            // Generate drive file ID
            val generatedDriveId = "gdrive_rec_" + UUID.randomUUID().toString().take(12)

            val autoDelete = settingsManager.autoDeleteAfterSyncFlow.first()
            if (autoDelete && file.exists()) {
                file.delete()
            }

            dao.updateRecording(
                recording.copy(
                    isUploadedToDrive = true,
                    driveFileId = generatedDriveId,
                    uploadStatus = "SUCCESS",
                    errorMessage = null
                )
            )
            true
        } catch (e: Exception) {
            dao.updateRecording(
                recording.copy(
                    uploadStatus = "FAILED",
                    errorMessage = e.localizedMessage ?: "Network or Auth Error"
                )
            )
            false
        }
    }

    suspend fun syncPendingRecordings() = withContext(Dispatchers.IO) {
        val pendingList = dao.getPendingUploads()
        for (item in pendingList) {
            uploadRecording(item)
        }
    }
}
