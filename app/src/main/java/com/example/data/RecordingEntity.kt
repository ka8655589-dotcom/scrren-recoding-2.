package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recordings")
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val fileName: String,
    val filePath: String,
    val durationSeconds: Long,
    val fileSizeBytes: Long,
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val chunkIndex: Int,
    val timeRangeTag: String, // e.g. "11:00 AM - 12:00 PM"
    val isUploadedToDrive: Boolean = false,
    val driveFileId: String? = null,
    val uploadStatus: String = "PENDING", // PENDING, UPLOADING, SUCCESS, FAILED
    val errorMessage: String? = null
)
