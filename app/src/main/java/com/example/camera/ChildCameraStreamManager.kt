package com.example.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.core.common.SafeLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Manages background and in-app CameraX frame capture for remote camera streaming.
 * Captures frames via ImageAnalysis, compresses to JPEG, and provides them to the P2P server.
 */
class ChildCameraStreamManager private constructor() {

    private val _isStreamingActive = MutableStateFlow(false)
    val isStreamingActive: StateFlow<Boolean> = _isStreamingActive.asStateFlow()

    private val _isTorchActive = MutableStateFlow(false)
    val isTorchActive: StateFlow<Boolean> = _isTorchActive.asStateFlow()

    private val _isFrontCamera = MutableStateFlow(false)
    val isFrontCamera: StateFlow<Boolean> = _isFrontCamera.asStateFlow()

    private val latestFrame = AtomicReference<ByteArray?>(null)
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var cameraExecutor: ExecutorService? = null

    private var currentLensFacing = CameraSelector.LENS_FACING_BACK
    private var lastFrameTime = 0L

    fun getLatestFrame(): ByteArray? = latestFrame.get()

    fun startStreaming(context: Context, useFrontCamera: Boolean = false) {
        if (_isStreamingActive.value) {
            SafeLogger.d(TAG, "Stream already active")
            return
        }

        currentLensFacing = if (useFrontCamera) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        _isFrontCamera.value = useFrontCamera
        _isStreamingActive.value = true

        cameraExecutor = Executors.newSingleThreadExecutor()

        CoroutineScope(Dispatchers.Main).launch {
            try {
                bindCamera(context.applicationContext)
            } catch (e: Exception) {
                SafeLogger.e(TAG, "Failed to start camera stream: ${e.message}", e)
                stopStreaming()
            }
        }
    }

    private suspend fun bindCamera(context: Context) = withContext(Dispatchers.Main) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val provider = providerFuture.get()
        cameraProvider = provider

        val selector = CameraSelector.Builder()
            .requireLensFacing(currentLensFacing)
            .build()

        val analysis = ImageAnalysis.Builder()
            .setTargetResolution(android.util.Size(640, 480))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        analysis.setAnalyzer(cameraExecutor ?: Executors.newSingleThreadExecutor()) { imageProxy ->
            processFrame(imageProxy)
        }

        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(
                ProcessLifecycleOwner.get(),
                selector,
                analysis
            )
            // Restore torch state if requested
            if (_isTorchActive.value && currentLensFacing == CameraSelector.LENS_FACING_BACK) {
                camera?.cameraControl?.enableTorch(true)
            }
            SafeLogger.i(TAG, "Camera streaming bound successfully (lens: $currentLensFacing)")
        } catch (e: Exception) {
            SafeLogger.e(TAG, "Error binding camera lifecycle: ${e.message}", e)
            _isStreamingActive.value = false
        }
    }

    private fun processFrame(imageProxy: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            // Throttle to ~15 FPS (66ms) to avoid local Wi-Fi congestion and conserve battery
            if (now - lastFrameTime < 60) {
                imageProxy.close()
                return
            }
            lastFrameTime = now

            val bitmap = imageProxy.toBitmap()
            val rotation = imageProxy.imageInfo.rotationDegrees

            val processedBitmap = if (rotation != 0) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            } else {
                bitmap
            }

            // Downscale slightly if needed for ultra-low latency streaming
            val targetWidth = 480
            val targetHeight = (targetWidth.toFloat() * processedBitmap.height / processedBitmap.width).toInt()
            val scaledBitmap = if (processedBitmap.width > targetWidth) {
                Bitmap.createScaledBitmap(processedBitmap, targetWidth, targetHeight, true)
            } else {
                processedBitmap
            }

            val bos = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 65, bos)
            val jpegBytes = bos.toByteArray()

            latestFrame.set(jpegBytes)
        } catch (e: Exception) {
            SafeLogger.w(TAG, "Error processing camera frame: ${e.message}")
        } finally {
            imageProxy.close()
        }
    }

    fun switchCamera(context: Context) {
        val newLens = if (currentLensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        currentLensFacing = newLens
        _isFrontCamera.value = (newLens == CameraSelector.LENS_FACING_FRONT)
        _isTorchActive.value = false // Front cameras rarely have torch

        if (_isStreamingActive.value) {
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    bindCamera(context.applicationContext)
                } catch (e: Exception) {
                    SafeLogger.e(TAG, "Failed switching camera: ${e.message}")
                }
            }
        }
    }

    fun toggleTorch(enabled: Boolean) {
        if (currentLensFacing == CameraSelector.LENS_FACING_FRONT) {
            _isTorchActive.value = false
            return
        }
        _isTorchActive.value = enabled
        camera?.cameraControl?.enableTorch(enabled)
    }

    fun stopStreaming() {
        _isStreamingActive.value = false
        _isTorchActive.value = false
        latestFrame.set(null)

        CoroutineScope(Dispatchers.Main).launch {
            try {
                cameraProvider?.unbindAll()
                camera = null
                cameraExecutor?.shutdown()
                cameraExecutor = null
                SafeLogger.i(TAG, "Camera streaming stopped and hardware released")
            } catch (e: Exception) {
                SafeLogger.e(TAG, "Error stopping camera stream: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "ChildCameraStreamer"

        @Volatile
        private var instance: ChildCameraStreamManager? = null

        fun getInstance(): ChildCameraStreamManager {
            return instance ?: synchronized(this) {
                instance ?: ChildCameraStreamManager().also { instance = it }
            }
        }
    }
}
