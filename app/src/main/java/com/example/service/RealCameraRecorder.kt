package com.example.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.core.content.ContextCompat
import java.io.File

/**
 * RealCameraRecorder captures genuine live video from the device's
 * physical front (selfie / face) or back camera hardware using Android Camera2 & MediaRecorder.
 */
class RealCameraRecorder(private val context: Context) {

    companion object {
        private const val TAG = "RealCameraRecorder"
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var mediaRecorder: MediaRecorder? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var isRecordingRealCamera = false
    private var currentFile: File? = null
    private var previewSurface: Surface? = null
    private var currentCameraId: String? = null
    private var activeFacing: Int = CameraCharacteristics.LENS_FACING_BACK

    fun isRecording(): Boolean = isRecordingRealCamera

    fun setPreviewSurface(surface: Surface?) {
        this.previewSurface = surface
    }

    private fun startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = HandlerThread("CameraBackgroundThread").apply {
                start()
                backgroundHandler = Handler(looper)
            }
        }
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join(1000)
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error stopping background thread: ${e.message}")
        }
    }

    private fun selectCamera(facingOption: String): String? {
        try {
            val cameraIds = cameraManager.cameraIdList
            if (cameraIds.isEmpty()) return null

            val targetFacing = if (facingOption.contains("Front", ignoreCase = true)) {
                CameraCharacteristics.LENS_FACING_FRONT
            } else {
                CameraCharacteristics.LENS_FACING_BACK
            }
            activeFacing = targetFacing

            for (id in cameraIds) {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (facing == targetFacing) {
                    return id
                }
            }
            return cameraIds.firstOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "Error selecting camera: ${e.message}", e)
            return null
        }
    }

    @SuppressLint("MissingPermission")
    fun startRealCameraRecording(
        outputFile: File,
        cameraOption: String,
        resolution: String,
        recordAudio: Boolean,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            onError("Camera permission is not granted")
            return
        }

        startBackgroundThread()
        currentFile = outputFile

        val selectedId = selectCamera(cameraOption)
        if (selectedId == null) {
            onError("No physical camera device detected on this hardware")
            return
        }
        currentCameraId = selectedId

        try {
            setupMediaRecorder(outputFile, resolution, recordAudio)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaRecorder: ${e.message}", e)
            onError("MediaRecorder init failed: ${e.message}")
            return
        }

        try {
            cameraManager.openCamera(selectedId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    startCaptureSession(onSuccess, onError)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "Camera disconnected")
                    camera.close()
                    cameraDevice = null
                    isRecordingRealCamera = false
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera open error: code $error")
                    camera.close()
                    cameraDevice = null
                    isRecordingRealCamera = false
                    onError("Camera device error code: $error")
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera: ${e.message}", e)
            onError("Failed to open camera: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun setupMediaRecorder(outputFile: File, resolution: String, recordAudio: Boolean) {
        mediaRecorder?.release()
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }

        val hasAudioPerm = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        var isAudioSourceSet = false
        if (recordAudio && hasAudioPerm) {
            try {
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
                isAudioSourceSet = true
            } catch (e: Exception) {
                try {
                    recorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
                    isAudioSourceSet = true
                } catch (e2: Exception) {
                    Log.w(TAG, "Audio source could not be set: ${e2.message}")
                }
            }
        }

        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        recorder.setOutputFile(outputFile.absolutePath)

        val (width, height, bitRate) = when (resolution) {
            "1080p" -> Triple(1920, 1080, 6_000_000)
            "720p" -> Triple(1280, 720, 3_000_000)
            "360p" -> Triple(640, 360, 1_000_000)
            "180p" -> Triple(320, 240, 500_000)
            else -> Triple(1280, 720, 3_000_000)
        }

        recorder.setVideoEncodingBitRate(bitRate)
        recorder.setVideoFrameRate(30)
        recorder.setVideoSize(width, height)
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)

        if (isAudioSourceSet) {
            try {
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                recorder.setAudioSamplingRate(44100)
                recorder.setAudioEncodingBitRate(128000)
            } catch (e: Exception) {
                Log.w(TAG, "Audio encoder config notice: ${e.message}")
            }
        }

        // Adjust video orientation based on camera lens
        try {
            if (activeFacing == CameraCharacteristics.LENS_FACING_FRONT) {
                recorder.setOrientationHint(270)
            } else {
                recorder.setOrientationHint(90)
            }
        } catch (e: Exception) {
            // ignore orientation hint failure
        }

        recorder.prepare()
        mediaRecorder = recorder
    }

    private fun startCaptureSession(onSuccess: () -> Unit, onError: (String) -> Unit) {
        val camera = cameraDevice
        val recorder = mediaRecorder
        if (camera == null || recorder == null) {
            onError("Camera or MediaRecorder is null")
            return
        }

        try {
            val recorderSurface = recorder.surface
            val surfaces = mutableListOf<Surface>(recorderSurface)
            previewSurface?.let {
                if (it.isValid) {
                    surfaces.add(it)
                }
            }

            camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                        requestBuilder.addTarget(recorderSurface)
                        previewSurface?.let {
                            if (it.isValid) {
                                requestBuilder.addTarget(it)
                            }
                        }

                        requestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                        session.setRepeatingRequest(requestBuilder.build(), null, backgroundHandler)

                        recorder.start()
                        isRecordingRealCamera = true
                        Log.i(TAG, "Real camera recording started successfully to: ${currentFile?.absolutePath}")
                        onSuccess()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start camera recording session: ${e.message}", e)
                        onError("Capture session start failed: ${e.message}")
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Camera capture session configuration failed")
                    onError("Camera capture session configuration failed")
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating camera capture session: ${e.message}", e)
            onError("Error creating camera capture session: ${e.message}")
        }
    }

    fun stopRecording(): Boolean {
        var stoppedSuccessfully = false
        try {
            if (isRecordingRealCamera && mediaRecorder != null) {
                mediaRecorder?.stop()
                stoppedSuccessfully = true
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Notice stopping mediaRecorder: ${e.message}")
        }

        try {
            captureSession?.close()
        } catch (e: Throwable) {
            Log.w(TAG, "Notice closing captureSession: ${e.message}")
        }
        captureSession = null

        try {
            cameraDevice?.close()
        } catch (e: Throwable) {
            Log.w(TAG, "Notice closing cameraDevice: ${e.message}")
        }
        cameraDevice = null

        try {
            mediaRecorder?.reset()
            mediaRecorder?.release()
        } catch (e: Throwable) {
            Log.w(TAG, "Notice releasing mediaRecorder: ${e.message}")
        }
        mediaRecorder = null

        stopBackgroundThread()
        isRecordingRealCamera = false
        return stoppedSuccessfully
    }
}
