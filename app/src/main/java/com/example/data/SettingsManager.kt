package com.example.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "recorder_settings")

class SettingsManager(private val context: Context) {

    companion object {
        val KEY_BATTERY_SHIELD_ENABLED = booleanPreferencesKey("battery_shield_enabled")
        val KEY_BATTERY_THRESHOLD = intPreferencesKey("battery_threshold")
        val KEY_SPLIT_DURATION_MINS = intPreferencesKey("split_duration_mins")
        val KEY_AUTO_UPLOAD_DRIVE = booleanPreferencesKey("auto_upload_drive")
        val KEY_RECORD_AUDIO = booleanPreferencesKey("record_audio")
        val KEY_MAX_RECORD_HOURS = intPreferencesKey("max_record_hours")
        val KEY_DRIVE_CONNECTED = booleanPreferencesKey("drive_connected")
        val KEY_DRIVE_ACCOUNT = stringPreferencesKey("drive_account")
        val KEY_DRIVE_FOLDER = stringPreferencesKey("drive_folder")
        val KEY_DRIVE_OAUTH_TOKEN = stringPreferencesKey("drive_oauth_token")
        val KEY_SERVICE_ACCOUNT_JSON = stringPreferencesKey("service_account_json")
        val KEY_VIDEO_RESOLUTION = stringPreferencesKey("video_resolution")
        val KEY_CAMERA_OPTION = stringPreferencesKey("camera_option")
        val KEY_AUTO_DELETE_AFTER_SYNC = booleanPreferencesKey("auto_delete_after_sync")
        val KEY_SAVE_TO_GALLERY = booleanPreferencesKey("save_to_gallery")
        val KEY_S23_STEALTH_MODE = booleanPreferencesKey("s23_stealth_mode")
        val KEY_AUTO_START_ON_BOOT = booleanPreferencesKey("auto_start_on_boot")
        val KEY_WAS_RECORDING = booleanPreferencesKey("was_recording")
    }

    val autoStartOnBootFlow: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_AUTO_START_ON_BOOT] ?: true
    }

    val wasRecordingFlow: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_WAS_RECORDING] ?: false
    }

    val batteryShieldEnabledFlow: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_BATTERY_SHIELD_ENABLED] ?: false
    }

    val autoDeleteAfterSyncFlow: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_AUTO_DELETE_AFTER_SYNC] ?: false
    }

    val saveToGalleryFlow: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_SAVE_TO_GALLERY] ?: false
    }

    val s23StealthModeFlow: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_S23_STEALTH_MODE] ?: true
    }

    val videoResolutionFlow: Flow<String> = context.dataStore.data.map {
        it[KEY_VIDEO_RESOLUTION] ?: "720p"
    }

    val cameraOptionFlow: Flow<String> = context.dataStore.data.map {
        it[KEY_CAMERA_OPTION] ?: "Screen Only"
    }

    val batteryThresholdFlow: Flow<Int> = context.dataStore.data.map {
        it[KEY_BATTERY_THRESHOLD] ?: 0
    }

    val splitDurationMinsFlow: Flow<Int> = context.dataStore.data.map {
        it[KEY_SPLIT_DURATION_MINS] ?: 60
    }

    val autoUploadDriveFlow: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_AUTO_UPLOAD_DRIVE] ?: true
    }

    val recordAudioFlow: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_RECORD_AUDIO] ?: true
    }

    val maxRecordHoursFlow: Flow<Int> = context.dataStore.data.map {
        it[KEY_MAX_RECORD_HOURS] ?: 24
    }

    val driveConnectedFlow: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_DRIVE_CONNECTED] ?: false
    }

    val driveAccountFlow: Flow<String> = context.dataStore.data.map {
        it[KEY_DRIVE_ACCOUNT] ?: "ka8655589@gmail.com"
    }

    val driveFolderFlow: Flow<String> = context.dataStore.data.map {
        it[KEY_DRIVE_FOLDER] ?: "Screen_Recordings_24H"
    }

    val driveOAuthTokenFlow: Flow<String> = context.dataStore.data.map {
        it[KEY_DRIVE_OAUTH_TOKEN] ?: ""
    }

    val serviceAccountJsonFlow: Flow<String> = context.dataStore.data.map {
        it[KEY_SERVICE_ACCOUNT_JSON] ?: ""
    }

    suspend fun setBatteryShieldEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_BATTERY_SHIELD_ENABLED] = enabled }
    }

    suspend fun setBatteryThreshold(value: Int) {
        context.dataStore.edit { it[KEY_BATTERY_THRESHOLD] = value }
    }

    suspend fun setSplitDurationMins(value: Int) {
        context.dataStore.edit { it[KEY_SPLIT_DURATION_MINS] = value }
    }

    suspend fun setAutoUploadDrive(value: Boolean) {
        context.dataStore.edit { it[KEY_AUTO_UPLOAD_DRIVE] = value }
    }

    suspend fun setRecordAudio(value: Boolean) {
        context.dataStore.edit { it[KEY_RECORD_AUDIO] = value }
    }

    suspend fun setDriveConnected(value: Boolean) {
        context.dataStore.edit { it[KEY_DRIVE_CONNECTED] = value }
    }

    suspend fun setDriveAccount(account: String) {
        context.dataStore.edit { it[KEY_DRIVE_ACCOUNT] = account }
    }

    suspend fun setDriveFolder(folder: String) {
        context.dataStore.edit { it[KEY_DRIVE_FOLDER] = folder }
    }

    suspend fun setDriveOAuthToken(token: String) {
        context.dataStore.edit { it[KEY_DRIVE_OAUTH_TOKEN] = token }
    }

    suspend fun setServiceAccountJson(json: String) {
        context.dataStore.edit { it[KEY_SERVICE_ACCOUNT_JSON] = json }
    }

    suspend fun setVideoResolution(resolution: String) {
        context.dataStore.edit { it[KEY_VIDEO_RESOLUTION] = resolution }
    }

    suspend fun setCameraOption(option: String) {
        context.dataStore.edit { it[KEY_CAMERA_OPTION] = option }
    }

    suspend fun setAutoDeleteAfterSync(value: Boolean) {
        context.dataStore.edit { it[KEY_AUTO_DELETE_AFTER_SYNC] = value }
    }

    suspend fun setSaveToGallery(value: Boolean) {
        context.dataStore.edit { it[KEY_SAVE_TO_GALLERY] = value }
    }

    suspend fun setS23StealthMode(value: Boolean) {
        context.dataStore.edit { it[KEY_S23_STEALTH_MODE] = value }
    }

    suspend fun setAutoStartOnBoot(value: Boolean) {
        context.dataStore.edit { it[KEY_AUTO_START_ON_BOOT] = value }
    }

    suspend fun setWasRecording(value: Boolean) {
        context.dataStore.edit { it[KEY_WAS_RECORDING] = value }
    }
}
