package com.example.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.TextureView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun CameraPreviewCard(
    cameraOption: String,
    isRecording: Boolean,
    onSwitchCameraOption: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isPreviewVisible by remember { mutableStateOf(false) }
    var isExpanded by remember { mutableStateOf(false) }
    val isFrontCamera = cameraOption.contains("Front", ignoreCase = true)

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (isRecording) Color(0xFFEF4444) else Color(0xFF10B981))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isFrontCamera) "Live Camera • Front / Face" else "Live Camera • Back Lens",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                Row {
                    // Switch Front / Back Camera
                    IconButton(
                        onClick = {
                            if (isFrontCamera) {
                                onSwitchCameraOption("Back Camera")
                            } else {
                                onSwitchCameraOption("Front Camera")
                            }
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Cameraswitch,
                            contentDescription = "Switch Camera",
                            tint = Color(0xFF38BDF8)
                        )
                    }

                    // Toggle Expand
                    IconButton(
                        onClick = { isExpanded = !isExpanded },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                            contentDescription = "Toggle Size",
                            tint = Color.White
                        )
                    }

                    // Hide / Show Preview
                    IconButton(
                        onClick = { isPreviewVisible = !isPreviewVisible },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = if (isPreviewVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = "Toggle View",
                            tint = Color(0xFF94A3B8)
                        )
                    }
                }
            }

            AnimatedVisibility(visible = !isPreviewVisible) {
                Column {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF1E293B),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.FlashOn,
                                contentDescription = null,
                                tint = Color(0xFF10B981),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Battery Saver Active: On-screen preview is off to save battery and reduce CPU/GPU heat. Real camera & microphone are recorded directly to MP4 in the background when recording.",
                                fontSize = 11.sp,
                                color = Color(0xFF94A3B8),
                                lineHeight = 15.sp
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(visible = isPreviewVisible) {
                Column {
                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(if (isExpanded) 1.2f else 1.77f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.Black)
                            .border(1.dp, Color(0xFF334155), RoundedCornerShape(14.dp))
                    ) {
                        if (isPreviewVisible) {
                            key(isFrontCamera) {
                                Camera2TexturePreview(
                                    isFrontFacing = isFrontCamera,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }

                        // Live Watermark Badge
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0x99000000),
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Videocam,
                                    contentDescription = null,
                                    tint = if (isRecording) Color(0xFFEF4444) else Color(0xFF10B981),
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (isRecording) "RECORDING REAL FEED" else "LIVE HARDWARE VIEW",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = null,
                                    tint = Color(0xFF38BDF8),
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "MIC ON",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF38BDF8)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Camera2TexturePreview(
    isFrontFacing: Boolean,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { ctx ->
            TextureView(ctx).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    private var cameraDevice: CameraDevice? = null
                    private var captureSession: CameraCaptureSession? = null
                    private var bgThread: HandlerThread? = null
                    private var bgHandler: Handler? = null

                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                        openCameraPreview(st)
                    }

                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}

                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        closeCameraPreview()
                        return true
                    }

                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

                    @SuppressLint("MissingPermission")
                    private fun openCameraPreview(st: SurfaceTexture) {
                        try {
                            val manager = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                            val cameraIds = manager.cameraIdList
                            if (cameraIds.isEmpty()) return

                            val targetFacing = if (isFrontFacing) {
                                CameraCharacteristics.LENS_FACING_FRONT
                            } else {
                                CameraCharacteristics.LENS_FACING_BACK
                            }

                            var chosenId: String? = null
                            for (id in cameraIds) {
                                val charac = manager.getCameraCharacteristics(id)
                                if (charac.get(CameraCharacteristics.LENS_FACING) == targetFacing) {
                                    chosenId = id
                                    break
                                }
                            }
                            if (chosenId == null) chosenId = cameraIds.firstOrNull() ?: return

                            bgThread = HandlerThread("CameraPreviewThread").apply {
                                start()
                                bgHandler = Handler(looper)
                            }

                            manager.openCamera(chosenId, object : CameraDevice.StateCallback() {
                                override fun onOpened(camera: CameraDevice) {
                                    cameraDevice = camera
                                    try {
                                        val surface = android.view.Surface(st)
                                        camera.createCaptureSession(
                                            listOf(surface),
                                            object : CameraCaptureSession.StateCallback() {
                                                override fun onConfigured(session: CameraCaptureSession) {
                                                    captureSession = session
                                                    try {
                                                        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                                        builder.addTarget(surface)
                                                        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                                                        session.setRepeatingRequest(builder.build(), null, bgHandler)
                                                    } catch (e: Throwable) {
                                                        Log.w("CameraPreview", "Preview request notice: ${e.message}")
                                                    }
                                                }

                                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                                    Log.w("CameraPreview", "Preview config failed")
                                                }

                                                override fun onClosed(session: CameraCaptureSession) {
                                                    super.onClosed(session)
                                                    captureSession = null
                                                }
                                            },
                                            bgHandler
                                        )
                                    } catch (e: Throwable) {
                                        Log.w("CameraPreview", "Capture session setup notice: ${e.message}")
                                    }
                                }

                                override fun onDisconnected(camera: CameraDevice) {
                                    try {
                                        camera.close()
                                    } catch (e: Throwable) {
                                        // Ignore close exception on disconnect
                                    }
                                    cameraDevice = null
                                }

                                override fun onError(camera: CameraDevice, error: Int) {
                                    try {
                                        camera.close()
                                    } catch (e: Throwable) {
                                        // Ignore close exception on error
                                    }
                                    cameraDevice = null
                                }
                            }, bgHandler)
                        } catch (e: Throwable) {
                            Log.w("CameraPreview", "Failed to start camera preview: ${e.message}")
                        }
                    }

                    private fun closeCameraPreview() {
                        try {
                            captureSession?.close()
                        } catch (e: Throwable) {
                            // ignore session close notice
                        }
                        captureSession = null

                        try {
                            cameraDevice?.close()
                        } catch (e: Throwable) {
                            // ignore device close notice
                        }
                        cameraDevice = null

                        try {
                            bgThread?.quitSafely()
                        } catch (e: Throwable) {
                            // ignore
                        }
                        bgThread = null
                        bgHandler = null
                    }
                }
            }
        },
        modifier = modifier
    )
}
