package com.example.domain.usecase

import com.example.core.common.AppResult
import com.example.core.common.SafeLogger
import com.example.core.permissions.PermissionManager
import com.example.domain.model.CommandExecutionResult
import com.example.domain.model.DeviceStatus
import com.example.domain.model.EnrollmentConsent
import com.example.domain.model.LinkedChildDevice
import com.example.domain.repository.ICommandRepository
import com.example.domain.repository.IDeviceDiagnosticsRepository
import com.example.domain.repository.IDeviceRepository
import com.example.domain.repository.IFlashlightController
import com.example.domain.repository.ILocationRepository
import com.example.domain.repository.INetworkRepository
import com.example.domain.repository.IUsageStatsRepository
import com.example.domain.repository.ICallLogRepository
import com.example.domain.repository.IMediaRepository

class SyncChildStatusUseCase(
    private val deviceRepository: IDeviceRepository,
    private val diagnosticsRepository: IDeviceDiagnosticsRepository,
    private val networkRepository: INetworkRepository,
    private val usageStatsRepository: IUsageStatsRepository,
    private val locationRepository: ILocationRepository,
    private val callLogRepository: ICallLogRepository,
    private val mediaRepository: IMediaRepository,
    private val permissionManager: PermissionManager
) {
    suspend operator fun invoke(deviceId: String): AppResult<DeviceStatus> {
        SafeLogger.i(TAG, "Starting periodic wellbeing status synchronization for device ${SafeLogger.maskId(deviceId)}")

        // 1. Diagnostics (battery, charging)
        val batteryLevel = diagnosticsRepository.getBatteryLevel()
        val isCharging = diagnosticsRepository.isDeviceCharging()
        val metadata = diagnosticsRepository.getDeviceMetadata()

        // 2. Network (Wi-Fi / Mobile Data)
        val wifiSsid = networkRepository.getConnectedWifiSsid()

        // 3. Digital Wellbeing (Screen Time)
        val usageResult = usageStatsRepository.getTodayAppUsage()
        val appUsageList = if (usageResult is AppResult.Success) usageResult.data else emptyList()
        val totalScreenTimeMs = appUsageList.sumOf { it.foregroundTimeMs }
        val hours = totalScreenTimeMs / (1000 * 60 * 60)
        val minutes = (totalScreenTimeMs / (1000 * 60)) % 60
        val formattedScreenTime = if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"

        // 4. Location (if permitted)
        var latitude: Double? = null
        var longitude: Double? = null
        var locationTimestamp: Long? = null
        if (permissionManager.hasLocationPermission()) {
            val locRes = locationRepository.getCurrentLocation()
            if (locRes is AppResult.Success && locRes.data != null) {
                latitude = locRes.data.first
                longitude = locRes.data.second
                locationTimestamp = System.currentTimeMillis()
            }
        }

        val status = DeviceStatus(
            lastSeen = System.currentTimeMillis(),
            batteryLevel = batteryLevel,
            isCharging = isCharging,
            latitude = latitude,
            longitude = longitude,
            locationTimestamp = locationTimestamp,
            wifiSsid = wifiSsid,
            totalScreenTimeMs = totalScreenTimeMs,
            totalScreenTimeFormatted = formattedScreenTime,
            appUsage = appUsageList
        )

        // Sync core status
        deviceRepository.syncStatus(deviceId, status)
        deviceRepository.syncMetadata(deviceId, metadata)
        deviceRepository.syncPermissions(deviceId, permissionManager.getPermissionMap())

        // Sync call log if explicitly permitted
        if (permissionManager.hasCallLogPermission()) {
            val callsRes = callLogRepository.getRecentCalls(10)
            if (callsRes is AppResult.Success) {
                deviceRepository.syncCalls(deviceId, callsRes.data)
            }
        }

        // Sync media metadata if explicitly permitted
        if (permissionManager.hasMediaPermission()) {
            val mediaRes = mediaRepository.getRecentMedia(10)
            if (mediaRes is AppResult.Success) {
                deviceRepository.syncMedia(deviceId, mediaRes.data)
            }
        }

        SafeLogger.i(TAG, "Wellbeing sync completed successfully")
        return AppResult.Success(status)
    }

    companion object {
        private const val TAG = "SyncChildStatusUseCase"
    }
}

class EnrollChildUseCase(
    private val deviceRepository: IDeviceRepository
) {
    suspend operator fun invoke(
        deviceId: String,
        childUid: String,
        parentUid: String,
        parentDisplayName: String,
        deviceName: String
    ): AppResult<Unit> {
        val consent = EnrollmentConsent(
            enabled = true,
            consentVersion = "1.0",
            consentTimestamp = System.currentTimeMillis(),
            parentUid = parentUid,
            parentDisplayName = parentDisplayName
        )
        // 1. Save consent on child node
        val res = deviceRepository.saveEnrollment(deviceId, consent)
        if (res is AppResult.Error) return res

        // 2. Register child under parent's linked devices list
        val linkedDevice = LinkedChildDevice(
            deviceId = deviceId,
            childUid = childUid,
            deviceName = deviceName,
            enrolledAt = System.currentTimeMillis(),
            status = "ACTIVE"
        )
        return deviceRepository.linkChildToParent(parentUid, linkedDevice)
    }
}

class ExecuteFlashlightCommandUseCase(
    private val flashlightController: IFlashlightController,
    private val commandRepository: ICommandRepository
) {
    suspend operator fun invoke(deviceId: String, enabled: Boolean): AppResult<Unit> {
        val toggleResult = flashlightController.setTorch(enabled)
        val success = toggleResult is AppResult.Success
        val errorMsg = if (toggleResult is AppResult.Error) toggleResult.error.toString() else null

        val result = CommandExecutionResult(
            commandName = "flashlight",
            success = success,
            executedAt = System.currentTimeMillis(),
            error = errorMsg
        )
        return commandRepository.acknowledgeFlashlightCommand(deviceId, result)
    }
}
