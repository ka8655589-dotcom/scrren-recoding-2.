package com.example.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.RecordingEntity
import com.example.viewmodel.MainViewModel
import java.io.File
import java.util.Locale

@Composable
fun RecordingsScreen(
    viewModel: MainViewModel,
    recordings: List<RecordingEntity>
) {
    val context = LocalContext.current
    var selectedFilter by remember { mutableStateOf("ALL") }
    var selectedVideoForPlayer by remember { mutableStateOf<RecordingEntity?>(null) }

    val filteredRecordings = when (selectedFilter) {
        "PENDING" -> recordings.filter { !it.isUploadedToDrive }
        "SYNCED" -> recordings.filter { it.isUploadedToDrive }
        else -> recordings
    }

    val formatFileSize: (Long) -> String = { bytes ->
        val mb = bytes / (1024f * 1024f)
        if (mb >= 1024) {
            String.format(Locale.getDefault(), "%.2f GB", mb / 1024f)
        } else if (mb >= 1.0) {
            String.format(Locale.getDefault(), "%.1f MB", mb)
        } else {
            val kb = bytes / 1024f
            String.format(Locale.getDefault(), "%.0f KB", kb)
        }
    }

    val formatDuration: (Long) -> String = { seconds ->
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        String.format(Locale.getDefault(), "%02d:%02d:%02d", h, m, s)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Recorded Clips",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${recordings.size} Total 1-Hour Chunks Saved",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (recordings.isNotEmpty()) {
                    OutlinedButton(
                        onClick = { viewModel.deleteAllRecordings(context) },
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete All", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Delete All", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }
                }

                Button(
                    onClick = { viewModel.syncAllPendingToDrive() },
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Icon(imageVector = Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Sync All", fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Filter Chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedFilter == "ALL",
                onClick = { selectedFilter = "ALL" },
                label = { Text("All (${recordings.size})") }
            )
            FilterChip(
                selected = selectedFilter == "PENDING",
                onClick = { selectedFilter = "PENDING" },
                label = { Text("Pending Drive (${recordings.count { !it.isUploadedToDrive }})") }
            )
            FilterChip(
                selected = selectedFilter == "SYNCED",
                onClick = { selectedFilter = "SYNCED" },
                label = { Text("Synced (${recordings.count { it.isUploadedToDrive }})") }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (filteredRecordings.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Movie,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No clips found in this category",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(filteredRecordings, key = { it.id }) { item ->
                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primaryContainer)
                                        .clickable { selectedVideoForPlayer = item },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = "Play Video",
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.fileName,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = "Time Range: ${item.timeRangeTag} • Chunk #${item.chunkIndex}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "Duration: ${formatDuration(item.durationSeconds)} • Size: ${formatFileSize(item.fileSizeBytes)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Drive Upload Status Banner
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = when (item.uploadStatus) {
                                    "SUCCESS" -> Color(0xFFDCFCE7)
                                    "UPLOADING" -> Color(0xFFE0F2FE)
                                    "FAILED" -> Color(0xFFFEE2E2)
                                    else -> Color(0xFFFEF3C7)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = when (item.uploadStatus) {
                                                    "SUCCESS" -> Icons.Default.CloudDone
                                                    "UPLOADING" -> Icons.Default.CloudSync
                                                    "FAILED" -> Icons.Default.Error
                                                    else -> Icons.Default.CloudUpload
                                                },
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                                tint = when (item.uploadStatus) {
                                                    "SUCCESS" -> Color(0xFF166534)
                                                    "UPLOADING" -> Color(0xFF0369A1)
                                                    "FAILED" -> Color(0xFF991B1B)
                                                    else -> Color(0xFF92400E)
                                                }
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            val isLocalExists = File(item.filePath).exists()
                                            Text(
                                                text = when (item.uploadStatus) {
                                                    "SUCCESS" -> if (isLocalExists) "Synced to Google Drive (Local Saved)" else "Synced to Google Drive (Local auto-deleted to save memory)"
                                                    "UPLOADING" -> "Uploading to Google Drive..."
                                                    "FAILED" -> "Upload Failed: ${item.errorMessage ?: "Network issue"}"
                                                    else -> "Pending Google Drive Upload"
                                                },
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = when (item.uploadStatus) {
                                                    "SUCCESS" -> Color(0xFF166534)
                                                    "UPLOADING" -> Color(0xFF0369A1)
                                                    "FAILED" -> Color(0xFF991B1B)
                                                    else -> Color(0xFF92400E)
                                                }
                                            )
                                        }
                                    }

                                    if (item.uploadStatus == "UPLOADING") {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        LinearProgressIndicator(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(4.dp)
                                                .clip(RoundedCornerShape(2.dp)),
                                            color = Color(0xFF0284C7)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Action Row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = { selectedVideoForPlayer = item }
                                    ) {
                                        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Play")
                                    }

                                    IconButton(
                                        onClick = { viewModel.saveVideoToGallery(context, item) }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Collections,
                                            contentDescription = "Save to Gallery",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }

                                if (!item.isUploadedToDrive) {
                                    Button(
                                        onClick = { viewModel.uploadRecordingToDrive(item) }
                                    ) {
                                        Icon(imageVector = Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Sync Drive")
                                    }
                                }

                                Row {
                                    IconButton(
                                        onClick = {
                                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                type = "video/mp4"
                                                putExtra(Intent.EXTRA_SUBJECT, item.fileName)
                                                putExtra(Intent.EXTRA_TEXT, "Screen Recording Clip: ${item.fileName} (${item.timeRangeTag})")
                                            }
                                            context.startActivity(Intent.createChooser(shareIntent, "Share Video Clip"))
                                        }
                                    ) {
                                        Icon(imageVector = Icons.Default.Share, contentDescription = "Share")
                                    }

                                    IconButton(
                                        onClick = { viewModel.deleteRecording(item) }
                                    ) {
                                        Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }

    selectedVideoForPlayer?.let { recording ->
        VideoPlayerDialog(
            recording = recording,
            onDismiss = { selectedVideoForPlayer = null },
            onUploadDrive = {
                viewModel.uploadRecordingToDrive(recording)
                selectedVideoForPlayer = null
            },
            onSaveToGallery = {
                viewModel.saveVideoToGallery(context, recording)
                selectedVideoForPlayer = null
            },
            onDeleteClip = {
                viewModel.deleteRecording(recording)
                selectedVideoForPlayer = null
            },
            onOpenExternal = {
                viewModel.openVideoInExternalPlayer(context, recording)
            },
            onShare = {
                viewModel.shareVideo(context, recording)
            }
        )
    }
}
