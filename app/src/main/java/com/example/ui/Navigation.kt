package com.example.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.RecordingsScreen
import com.example.ui.screens.SettingsScreen
import com.example.viewmodel.MainViewModel

sealed class Screen(val route: String, val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    object Dashboard : Screen("dashboard", "Recorder", Icons.Default.FiberManualRecord)
    object Recordings : Screen("recordings", "Clips", Icons.Default.Movie)
    object Settings : Screen("settings", "Settings", Icons.Default.Settings)
}

@Composable
fun AppNavigation(viewModel: MainViewModel) {
    val navController = rememberNavController()

    val isRecording by viewModel.isRecording.collectAsStateWithLifecycle()
    val totalDuration by viewModel.totalDurationSeconds.collectAsStateWithLifecycle()
    val chunkDuration by viewModel.chunkDurationSeconds.collectAsStateWithLifecycle()
    val batteryLevel by viewModel.currentBatteryLevel.collectAsStateWithLifecycle()
    val batteryShieldEnabled by viewModel.batteryShieldEnabled.collectAsStateWithLifecycle()
    val batteryThreshold by viewModel.batteryThreshold.collectAsStateWithLifecycle()
    val chunkIndex by viewModel.currentChunkIndex.collectAsStateWithLifecycle()
    val timeRangeTag by viewModel.activeTimeRangeTag.collectAsStateWithLifecycle()
    val lastStopReason by viewModel.lastStopReason.collectAsStateWithLifecycle()
    val recordings by viewModel.recordingsList.collectAsStateWithLifecycle()

    val splitDurationMins by viewModel.splitDurationMins.collectAsStateWithLifecycle()
    val autoUploadDrive by viewModel.autoUploadDrive.collectAsStateWithLifecycle()
    val recordAudio by viewModel.recordAudio.collectAsStateWithLifecycle()
    val driveConnected by viewModel.driveConnected.collectAsStateWithLifecycle()
    val driveAccount by viewModel.driveAccount.collectAsStateWithLifecycle()
    val driveFolder by viewModel.driveFolder.collectAsStateWithLifecycle()
    val driveOAuthToken by viewModel.driveOAuthToken.collectAsStateWithLifecycle()
    val serviceAccountJson by viewModel.serviceAccountJson.collectAsStateWithLifecycle()
    val videoResolution by viewModel.videoResolution.collectAsStateWithLifecycle()
    val cameraOption by viewModel.cameraOption.collectAsStateWithLifecycle()
    val autoDeleteAfterSync by viewModel.autoDeleteAfterSync.collectAsStateWithLifecycle()
    val saveToGallery by viewModel.saveToGallery.collectAsStateWithLifecycle()
    val s23StealthMode by viewModel.s23StealthMode.collectAsStateWithLifecycle()

    val items = listOf(
        Screen.Dashboard,
        Screen.Recordings,
        Screen.Settings
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route

                items.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(imageVector = screen.icon, contentDescription = screen.title) },
                        label = { Text(screen.title) },
                        selected = currentRoute == screen.route,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Dashboard.route,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            composable(Screen.Dashboard.route) {
                HomeScreen(
                    viewModel = viewModel,
                    isRecording = isRecording,
                    totalDuration = totalDuration,
                    chunkDuration = chunkDuration,
                    batteryLevel = batteryLevel,
                    batteryThreshold = batteryThreshold,
                    batteryShieldEnabled = batteryShieldEnabled,
                    chunkIndex = chunkIndex,
                    timeRangeTag = timeRangeTag,
                    lastStopReason = lastStopReason,
                    recordings = recordings,
                    cameraOption = cameraOption,
                    s23StealthMode = s23StealthMode,
                    onNavigateToRecordings = {
                        navController.navigate(Screen.Recordings.route)
                    }
                )
            }

            composable(Screen.Recordings.route) {
                RecordingsScreen(
                    viewModel = viewModel,
                    recordings = recordings
                )
            }

            composable(Screen.Settings.route) {
                SettingsScreen(
                    viewModel = viewModel,
                    batteryThreshold = batteryThreshold,
                    batteryShieldEnabled = batteryShieldEnabled,
                    splitDurationMins = splitDurationMins,
                    autoUploadDrive = autoUploadDrive,
                    recordAudio = recordAudio,
                    driveConnected = driveConnected,
                    driveAccount = driveAccount,
                    driveFolder = driveFolder,
                    driveOAuthToken = driveOAuthToken,
                    serviceAccountJson = serviceAccountJson,
                    videoResolution = videoResolution,
                    cameraOption = cameraOption,
                    autoDeleteAfterSync = autoDeleteAfterSync,
                    saveToGallery = saveToGallery,
                    s23StealthMode = s23StealthMode
                )
            }
        }
    }
}
