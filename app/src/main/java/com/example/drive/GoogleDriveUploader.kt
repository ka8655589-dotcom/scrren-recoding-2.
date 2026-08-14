package com.example.drive

import android.content.Context
import android.util.Log
import com.example.data.AppDatabase
import com.example.data.RecordingEntity
import com.example.data.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class GoogleDriveUploader(private val context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val dao = db.recordingDao()
    private val settingsManager = SettingsManager(context)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "GoogleDriveUploader"
        private const val DRIVE_API_FILES = "https://www.googleapis.com/drive/v3/files"
        private const val DRIVE_UPLOAD_API = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart"
    }

    /**
     * Uploads a recording to Google Drive.
     * If an OAuth access token is configured, performs a real Google Drive REST API v3 upload.
     * If no token is provided, safely marks status as PENDING_AUTH with helpful guidance.
     */
    suspend fun uploadRecording(recording: RecordingEntity): Boolean = withContext(Dispatchers.IO) {
        val file = File(recording.filePath)
        if (!file.exists()) {
            dao.updateRecording(
                recording.copy(
                    uploadStatus = "FAILED",
                    errorMessage = "Local video file not found on device storage"
                )
            )
            return@withContext false
        }

        val oauthToken = settingsManager.driveOAuthTokenFlow.first().trim()
        val folderName = settingsManager.driveFolderFlow.first().ifBlank { "Screen_Recordings_24H" }

        if (oauthToken.isBlank()) {
            dao.updateRecording(
                recording.copy(
                    uploadStatus = "PENDING_AUTH",
                    errorMessage = "Drive Token needed for auto-upload. Use 'Share' to upload via Drive App or add token in Settings."
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
            // 1. Get or create the destination folder in Google Drive
            val folderId = getOrCreateFolder(folderName, oauthToken)
            if (folderId == null) {
                dao.updateRecording(
                    recording.copy(
                        uploadStatus = "FAILED",
                        errorMessage = "Could not create/access folder '$folderName' in Google Drive. Please check token permissions."
                    )
                )
                return@withContext false
            }

            // 2. Perform multipart upload to Google Drive
            val metadataJson = JSONObject().apply {
                put("name", file.name)
                put("parents", org.json.JSONArray().apply { put(folderId) })
                put("mimeType", "video/mp4")
            }.toString()

            val metadataPart = metadataJson.toRequestBody("application/json; charset=UTF-8".toMediaTypeOrNull())
            val filePart = file.asRequestBody("video/mp4".toMediaTypeOrNull())

            val multipartBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("metadata", null, metadataPart)
                .addFormDataPart("file", file.name, filePart)
                .build()

            val uploadRequest = Request.Builder()
                .url(DRIVE_UPLOAD_API)
                .addHeader("Authorization", "Bearer $oauthToken")
                .post(multipartBody)
                .build()

            val response = httpClient.newCall(uploadRequest).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val jsonResponse = JSONObject(responseBody)
                val driveFileId = jsonResponse.optString("id", "")

                val autoDelete = settingsManager.autoDeleteAfterSyncFlow.first()
                if (autoDelete && file.exists()) {
                    file.delete()
                }

                dao.updateRecording(
                    recording.copy(
                        isUploadedToDrive = true,
                        driveFileId = driveFileId,
                        uploadStatus = "SUCCESS",
                        errorMessage = null
                    )
                )
                Log.i(TAG, "Successfully uploaded ${file.name} to Google Drive! File ID: $driveFileId")
                true
            } else {
                val errorDetail = try {
                    val errJson = JSONObject(responseBody)
                    val errorObj = errJson.optJSONObject("error")
                    val message = errorObj?.optString("message") ?: "HTTP ${response.code}"
                    val errorsArray = errorObj?.optJSONArray("errors")
                    val reason = if (errorsArray != null && errorsArray.length() > 0) {
                        errorsArray.getJSONObject(0).optString("reason", "")
                    } else ""
                    
                    if (reason == "storageQuotaExceeded" || message.contains("quota", ignoreCase = true) || response.code == 507) {
                        "Google Drive storage is full (Quota Exceeded). Switch to a new account with free space or free up storage."
                    } else {
                        "Google Drive Upload Error: $message"
                    }
                } catch (e: Exception) {
                    "HTTP ${response.code}: $responseBody"
                }

                dao.updateRecording(
                    recording.copy(
                        uploadStatus = "FAILED",
                        errorMessage = errorDetail
                    )
                )
                Log.e(TAG, "Upload failed for ${file.name}: $errorDetail")
                false
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Exception uploading ${file.name} to Google Drive: ${e.message}", e)
            dao.updateRecording(
                recording.copy(
                    uploadStatus = "FAILED",
                    errorMessage = e.localizedMessage ?: "Network or Drive connection error"
                )
            )
            false
        }
    }

    /**
     * Checks if a folder exists with the given name; if not, creates it.
     * Returns the Google Drive folder ID, or null on error.
     */
    suspend fun getOrCreateFolder(folderName: String, token: String): String? = withContext(Dispatchers.IO) {
        try {
            // Search for existing folder
            val searchUrl = "$DRIVE_API_FILES?q=name%3D'${folderName}'%20and%20mimeType%3D'application%2Fvnd.google-apps.folder'%20and%20trashed%3Dfalse&fields=files(id%2Cname)"
            val searchRequest = Request.Builder()
                .url(searchUrl)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            val searchResponse = httpClient.newCall(searchRequest).execute()
            if (searchResponse.isSuccessful) {
                val body = searchResponse.body?.string() ?: ""
                val json = JSONObject(body)
                val files = json.optJSONArray("files")
                if (files != null && files.length() > 0) {
                    return@withContext files.getJSONObject(0).optString("id")
                }
            }

            // Create folder
            val createJson = JSONObject().apply {
                put("name", folderName)
                put("mimeType", "application/vnd.google-apps.folder")
            }.toString()

            val createBody = createJson.toRequestBody("application/json; charset=UTF-8".toMediaTypeOrNull())
            val createRequest = Request.Builder()
                .url(DRIVE_API_FILES)
                .addHeader("Authorization", "Bearer $token")
                .post(createBody)
                .build()

            val createResponse = httpClient.newCall(createRequest).execute()
            if (createResponse.isSuccessful) {
                val createBodyStr = createResponse.body?.string() ?: ""
                val createdJson = JSONObject(createBodyStr)
                return@withContext createdJson.optString("id")
            } else {
                Log.e(TAG, "Failed to create folder '$folderName': code ${createResponse.code}")
                return@withContext null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in getOrCreateFolder: ${e.message}", e)
            return@withContext null
        }
    }

    /**
     * Tests Drive connectivity and attempts to verify/create the designated folder.
     */
    suspend fun testDriveConnection(token: String, folderName: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (token.isBlank()) {
            return@withContext Pair(false, "OAuth token is empty")
        }
        try {
            val folderId = getOrCreateFolder(folderName, token)
            if (folderId != null) {
                Pair(true, "Successfully connected! Google Drive folder '$folderName' is ready (ID: $folderId).")
            } else {
                Pair(false, "Could not verify/create folder in Google Drive. Check token scopes.")
            }
        } catch (e: Exception) {
            Pair(false, "Connection error: ${e.localizedMessage}")
        }
    }

    suspend fun syncPendingRecordings() = withContext(Dispatchers.IO) {
        val pendingList = dao.getPendingUploads()
        for (item in pendingList) {
            uploadRecording(item)
        }
    }
}
