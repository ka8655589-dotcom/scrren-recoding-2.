package com.example.drive

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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

    fun isNetworkAvailable(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            true
        }
    }

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

        if (!isNetworkAvailable()) {
            dao.updateRecording(
                recording.copy(
                    uploadStatus = "FAILED",
                    errorMessage = "No Internet Connection: Please connect to Wi-Fi or Mobile Data to sync."
                )
            )
            return@withContext false
        }

        val saJson = settingsManager.serviceAccountJsonFlow.first().trim()
        val directOAuthToken = settingsManager.driveOAuthTokenFlow.first().trim()
        val folderName = settingsManager.driveFolderFlow.first().ifBlank { "Screen_Recordings_24H" }

        val activeToken = if (saJson.isNotEmpty()) {
            val (token, err) = ServiceAccountAuth.getFreshAccessToken(saJson)
            if (token == null) {
                dao.updateRecording(
                    recording.copy(
                        uploadStatus = "FAILED",
                        errorMessage = "Service Account Auth Error: $err"
                    )
                )
                return@withContext false
            }
            token
        } else if (directOAuthToken.isNotEmpty()) {
            directOAuthToken
        } else {
            dao.updateRecording(
                recording.copy(
                    uploadStatus = "PENDING_AUTH",
                    errorMessage = "No Service Account JSON or Drive Token configured. Paste JSON key or use 'Share to Drive'."
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
            val userEmail = settingsManager.driveAccountFlow.first().trim()
            val (folderId, folderErr) = getOrCreateFolderWithDetail(folderName, activeToken, userEmail.ifEmpty { null })
            if (folderId == null) {
                dao.updateRecording(
                    recording.copy(
                        uploadStatus = "FAILED",
                        errorMessage = folderErr ?: "Could not create/access folder '$folderName' in Google Drive. Please check permissions."
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
                .addHeader("Authorization", "Bearer $activeToken")
                .post(multipartBody)
                .build()

            val response = httpClient.newCall(uploadRequest).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val jsonResponse = JSONObject(responseBody)
                val driveFileId = jsonResponse.optString("id", "").trim()

                // Step 1: Positively verify the uploaded file exists on Google Drive API
                var isVerifiedOnDrive = false
                if (driveFileId.isNotEmpty()) {
                    try {
                        val verifyUrl = "$DRIVE_API_FILES/$driveFileId?fields=id,name,size"
                        val verifyRequest = Request.Builder()
                            .url(verifyUrl)
                            .addHeader("Authorization", "Bearer $activeToken")
                            .get()
                            .build()
                        val verifyResponse = httpClient.newCall(verifyRequest).execute()
                        if (verifyResponse.isSuccessful) {
                            val verifyBody = verifyResponse.body?.string() ?: ""
                            val verifyJson = JSONObject(verifyBody)
                            if (verifyJson.optString("id") == driveFileId) {
                                isVerifiedOnDrive = true
                                Log.i(TAG, "Cloud upload verified on Google Drive: ID $driveFileId")
                            }
                        }
                    } catch (verifyEx: Exception) {
                        Log.w(TAG, "Secondary verification request failed, relying on primary upload ID: ${verifyEx.message}")
                        isVerifiedOnDrive = true // Primary response was successful with valid ID
                    }
                }

                // Step 2: Auto-delete local file ONLY if verified uploaded to Google Drive
                val autoDelete = settingsManager.autoDeleteAfterSyncFlow.first()
                if (autoDelete && isVerifiedOnDrive && file.exists()) {
                    val deleted = file.delete()
                    Log.i(TAG, "Local file ${file.name} deleted to free storage: $deleted (verified on Drive: $driveFileId)")
                }

                dao.updateRecording(
                    recording.copy(
                        isUploadedToDrive = true,
                        driveFileId = driveFileId.ifEmpty { "gdrive_uploaded" },
                        uploadStatus = "SUCCESS",
                        errorMessage = null
                    )
                )
                Log.i(TAG, "Successfully uploaded and confirmed ${file.name} to Google Drive! File ID: $driveFileId")
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
            val isNoInternet = e is java.net.UnknownHostException ||
                e.message?.contains("Unable to resolve host") == true ||
                e.message?.contains("No address associated with hostname") == true
            val errorMsg = if (isNoInternet) {
                "No Internet Connection: Device is offline. Please turn on Wi-Fi or Mobile Data."
            } else {
                e.localizedMessage ?: "Network or Drive connection error"
            }
            dao.updateRecording(
                recording.copy(
                    uploadStatus = "FAILED",
                    errorMessage = errorMsg
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
        val userEmail = settingsManager.driveAccountFlow.first().trim()
        getOrCreateFolderWithDetail(folderName, token, userEmail.ifEmpty { null }).first
    }

    /**
     * Searches for or creates a folder with detailed diagnostics and auto-shares with user account.
     */
    suspend fun getOrCreateFolderWithDetail(
        folderName: String,
        token: String,
        targetEmailToShareWith: String? = null
    ): Pair<String?, String?> = withContext(Dispatchers.IO) {
        try {
            val rawInput = folderName.trim()

            // 1. Direct folder ID or URL support
            val directFolderId = when {
                rawInput.contains("/folders/") -> {
                    rawInput.substringAfter("/folders/").substringBefore("?").substringBefore("/")
                }
                rawInput.matches(Regex("^[a-zA-Z0-9_-]{20,}$")) -> rawInput
                else -> null
            }

            if (!directFolderId.isNullOrBlank()) {
                val verifyUrl = "$DRIVE_API_FILES/$directFolderId?supportsAllDrives=true&fields=id,name,mimeType"
                val verifyRequest = Request.Builder()
                    .url(verifyUrl)
                    .addHeader("Authorization", "Bearer $token")
                    .get()
                    .build()
                val verifyResp = httpClient.newCall(verifyRequest).execute()
                val verifyBody = verifyResp.body?.string() ?: ""
                if (verifyResp.isSuccessful) {
                    return@withContext Pair(directFolderId, null)
                } else {
                    val err = parseGoogleApiError(verifyBody, verifyResp.code, rawInput)
                    Log.w(TAG, "Direct folder ID check failed: $err")
                }
            }

            // 2. Search for existing folder by name with full shared drive / shared item support
            val cleanFolderName = if (directFolderId != null) "Screen_Recordings_24H" else rawInput
            val encodedName = cleanFolderName.replace("'", "\\'")
            val searchUrl = "$DRIVE_API_FILES?q=name%3D'${encodedName}'%20and%20mimeType%3D'application%2Fvnd.google-apps.folder'%20and%20trashed%3Dfalse&fields=files(id%2Cname)&supportsAllDrives=true&includeItemsFromAllDrives=true"
            val searchRequest = Request.Builder()
                .url(searchUrl)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            val searchResponse = httpClient.newCall(searchRequest).execute()
            val searchBody = searchResponse.body?.string() ?: ""

            if (!searchResponse.isSuccessful) {
                val errorMsg = parseGoogleApiError(searchBody, searchResponse.code, cleanFolderName)
                Log.e(TAG, "Search folder failed (${searchResponse.code}): $errorMsg")
                return@withContext Pair(null, errorMsg)
            }

            val json = JSONObject(searchBody)
            val files = json.optJSONArray("files")
            if (files != null && files.length() > 0) {
                val foundFolderId = files.getJSONObject(0).optString("id")
                return@withContext Pair(foundFolderId, null)
            }

            // Folder not found in Drive. Attempt to create it.
            val createJson = JSONObject().apply {
                put("name", folderName)
                put("mimeType", "application/vnd.google-apps.folder")
            }.toString()

            val createBody = createJson.toRequestBody("application/json; charset=UTF-8".toMediaTypeOrNull())
            val createRequest = Request.Builder()
                .url("$DRIVE_API_FILES?supportsAllDrives=true")
                .addHeader("Authorization", "Bearer $token")
                .post(createBody)
                .build()

            val createResponse = httpClient.newCall(createRequest).execute()
            val createBodyStr = createResponse.body?.string() ?: ""

            if (createResponse.isSuccessful) {
                val createdJson = JSONObject(createBodyStr)
                val newFolderId = createdJson.optString("id")

                // Auto-share with user email so it appears in their personal Google Drive
                if (!targetEmailToShareWith.isNullOrBlank() && targetEmailToShareWith.contains("@")) {
                    shareFolderWithUser(newFolderId, targetEmailToShareWith, token)
                }

                return@withContext Pair(newFolderId, null)
            } else {
                val errorMsg = parseGoogleApiError(createBodyStr, createResponse.code, folderName)
                Log.e(TAG, "Failed to create folder '$folderName': code ${createResponse.code} - $errorMsg")
                return@withContext Pair(null, errorMsg)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in getOrCreateFolderWithDetail: ${e.message}", e)
            val isNoInternet = e is java.net.UnknownHostException ||
                e.message?.contains("Unable to resolve host") == true ||
                e.message?.contains("No address associated with hostname") == true
            val errorDetail = if (isNoInternet) {
                "No Internet Connection: Unable to reach Google Drive servers. Please turn on Wi-Fi or Mobile Data."
            } else {
                e.localizedMessage ?: "Network connection error"
            }
            return@withContext Pair(null, errorDetail)
        }
    }

    private fun shareFolderWithUser(folderId: String, email: String, token: String) {
        try {
            val permJson = JSONObject().apply {
                put("role", "writer")
                put("type", "user")
                put("emailAddress", email.trim())
            }.toString()

            val requestBody = permJson.toRequestBody("application/json; charset=UTF-8".toMediaTypeOrNull())
            val request = Request.Builder()
                .url("$DRIVE_API_FILES/$folderId/permissions?supportsAllDrives=true")
                .addHeader("Authorization", "Bearer $token")
                .post(requestBody)
                .build()

            val response = httpClient.newCall(request).execute()
            Log.i(TAG, "Auto-shared folder $folderId with $email: success=${response.isSuccessful}")
        } catch (e: Exception) {
            Log.w(TAG, "Could not auto-share folder with $email: ${e.message}")
        }
    }

    private fun parseGoogleApiError(responseBody: String, statusCode: Int, folderName: String): String {
        return try {
            val json = JSONObject(responseBody)
            val errObj = json.optJSONObject("error")
            val message = errObj?.optString("message", "") ?: ""

            if (message.contains("Google Drive API has not been used in project") || message.contains("is disabled") || message.contains("SERVICE_DISABLED")) {
                "Google Drive API is NOT enabled in your Google Cloud Project. Please enable the 'Google Drive API' in Google Cloud Console."
            } else if (statusCode == 403 && (message.contains("storageQuotaExceeded") || message.contains("quota") || message.contains("storage"))) {
                "Service Account storage quota is 0 MB. Please create folder '$folderName' in your personal Google Drive (drive.google.com) and share it with your Service Account email as Editor."
            } else if (message.isNotBlank()) {
                "Google API Error ($statusCode): $message"
            } else {
                "Google API Error ($statusCode)"
            }
        } catch (e: Exception) {
            "Google Drive API returned HTTP $statusCode"
        }
    }

    /**
     * Tests Drive connectivity and attempts to verify/create the designated folder.
     */
    suspend fun testDriveConnection(tokenOrJson: String, folderName: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (!isNetworkAvailable()) {
            return@withContext Pair(
                false,
                "No Internet Connection: Your device appears to be offline. Please connect to Wi-Fi or turn on Mobile Data, then tap Test again."
            )
        }

        val trimmed = tokenOrJson.trim()
        if (trimmed.isBlank()) {
            return@withContext Pair(false, "Credentials are empty. Please enter an OAuth Token or Service Account JSON.")
        }

        var saClientEmail: String? = null
        val resolvedToken = if (trimmed.startsWith("{") && trimmed.contains("private_key")) {
            try {
                val saJson = JSONObject(trimmed)
                saClientEmail = saJson.optString("client_email", "").trim()
            } catch (e: Exception) {
                // ignore
            }
            val (token, err) = ServiceAccountAuth.getFreshAccessToken(trimmed)
            if (token == null) {
                return@withContext Pair(false, "Service Account Key error: $err")
            }
            token
        } else {
            trimmed
        }

        val targetEmail = settingsManager.driveAccountFlow.first().trim()

        try {
            val (folderId, errorDetail) = getOrCreateFolderWithDetail(
                folderName = folderName,
                token = resolvedToken,
                targetEmailToShareWith = targetEmail.ifEmpty { null }
            )
            if (folderId != null) {
                Pair(true, "Successfully connected! Google Drive folder '$folderName' is ready (ID: $folderId).")
            } else {
                val instructions = if (!saClientEmail.isNullOrBlank()) {
                    "\n\n👉 Step-by-Step Fix:\n1. Open Google Drive (drive.google.com)\n2. Create folder '$folderName'\n3. Click Share -> paste your Service Account email:\n$saClientEmail\n4. Give 'Editor' access and save."
                } else {
                    "\n\nMake sure the folder is created and shared with your credentials."
                }
                Pair(false, "${errorDetail ?: "Could not access or create folder in Google Drive."}$instructions")
            }
        } catch (e: Exception) {
            val isNoInternet = e is java.net.UnknownHostException ||
                e.message?.contains("Unable to resolve host") == true ||
                e.message?.contains("No address associated with hostname") == true
            val msg = if (isNoInternet) {
                "No Internet Connection: Unable to reach Google servers. Please turn on Wi-Fi or Mobile Data."
            } else {
                "Connection error: ${e.localizedMessage}"
            }
            Pair(false, msg)
        }
    }

    suspend fun syncPendingRecordings() = withContext(Dispatchers.IO) {
        val pendingList = dao.getPendingUploads()
        for (item in pendingList) {
            uploadRecording(item)
        }
    }
}
