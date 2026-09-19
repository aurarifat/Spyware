package com.example.data.repository

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import com.example.core.common.AppError
import com.example.core.common.AppResult
import com.example.core.common.SafeLogger
import com.example.domain.repository.IFlashlightController

/**
 * Flashlight Controller.
 * Uses system [CameraManager] torch mode API.
 * Never activates video/image sensors; strictly toggles physical LED flash.
 */
class FlashlightControllerImpl(
    private val context: Context
) : IFlashlightController {

    private val cameraManager by lazy {
        context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    }

    private var cameraIdWithFlash: String? = null
    private var _isTorchOn: Boolean = false

    override val isTorchOn: Boolean
        get() = _isTorchOn

    override val isAvailable: Boolean
        get() = resolveCameraIdWithFlash() != null

    init {
        resolveCameraIdWithFlash()
    }

    private fun resolveCameraIdWithFlash(): String? {
        if (cameraIdWithFlash != null) return cameraIdWithFlash
        val cm = cameraManager ?: return null
        return try {
            for (id in cm.cameraIdList) {
                val characteristics = cm.getCameraCharacteristics(id)
                val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (hasFlash && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraIdWithFlash = id
                    return id
                }
            }
            // If no back camera found with flash, check any
            for (id in cm.cameraIdList) {
                val characteristics = cm.getCameraCharacteristics(id)
                if (characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) {
                    cameraIdWithFlash = id
                    return id
                }
            }
            null
        } catch (e: Exception) {
            SafeLogger.w(TAG, "Error resolving camera with flash: ${e.message}")
            null
        }
    }

    override suspend fun setTorch(enabled: Boolean): AppResult<Boolean> {
        val cm = cameraManager ?: return AppResult.Error(AppError.CameraUnavailable("CameraManager service unavailable"))
        val id = resolveCameraIdWithFlash() ?: return AppResult.Error(AppError.HardwareNotSupported("No camera flash hardware found on device"))

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                cm.setTorchMode(id, enabled)
                _isTorchOn = enabled
                SafeLogger.i(TAG, "Flashlight torch mode set to: $enabled")
                AppResult.Success(enabled)
            } else {
                AppResult.Error(AppError.HardwareNotSupported("Torch mode requires Android 6.0+"))
            }
        } catch (e: CameraAccessException) {
            SafeLogger.e(TAG, "CameraAccessException: ${e.message}")
            AppResult.Error(AppError.CameraUnavailable("Camera in use by another application"))
        } catch (e: Exception) {
            SafeLogger.e(TAG, "Unexpected error toggling torch: ${e.message}")
            AppResult.Error(AppError.UnknownError(e.message ?: "Failed to set torch mode"))
        }
    }

    companion object {
        private const val TAG = "FlashlightController"
    }
}
