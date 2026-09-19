package com.example.domain.repository

import com.example.core.common.AppResult
import com.example.domain.model.AppUsageEntry
import com.example.domain.model.CallEntry
import com.example.domain.model.ChildDeviceFullState
import com.example.domain.model.CommandExecutionResult
import com.example.domain.model.DeviceMetadata
import com.example.domain.model.DeviceStatus
import com.example.domain.model.EnrollmentConsent
import com.example.domain.model.LinkedChildDevice
import com.example.domain.model.MediaEntry
import com.example.domain.model.RemoteFlashlightCommand
import com.example.domain.model.RemoteRefreshCommand
import com.example.domain.model.UserProfile
import com.example.domain.model.UserRole
import kotlinx.coroutines.flow.Flow

interface IAuthRepository {
    fun getCurrentUid(): String?
    fun getCurrentUser(): UserProfile?
    suspend fun signInAnonymously(): AppResult<UserProfile>
    suspend fun signInWithEmail(email: String, pass: String): AppResult<UserProfile>
    suspend fun registerWithEmail(email: String, pass: String, role: UserRole, displayName: String): AppResult<UserProfile>
    suspend fun signOut()
}

interface IDeviceRepository {
    suspend fun saveEnrollment(deviceId: String, consent: EnrollmentConsent): AppResult<Unit>
    suspend fun unlinkDevice(parentUid: String, deviceId: String): AppResult<Unit>
    suspend fun syncStatus(deviceId: String, status: DeviceStatus): AppResult<Unit>
    suspend fun syncMetadata(deviceId: String, metadata: DeviceMetadata): AppResult<Unit>
    suspend fun syncPermissions(deviceId: String, permissions: Map<String, Boolean>): AppResult<Unit>
    suspend fun syncCalls(deviceId: String, calls: List<CallEntry>): AppResult<Unit>
    suspend fun syncMedia(deviceId: String, media: List<MediaEntry>): AppResult<Unit>
    fun observeDeviceFullState(deviceId: String): Flow<ChildDeviceFullState?>
    fun observeParentLinkedDevices(parentUid: String): Flow<List<LinkedChildDevice>>
    suspend fun linkChildToParent(parentUid: String, device: LinkedChildDevice): AppResult<Unit>
    suspend fun getEnrollment(deviceId: String): AppResult<EnrollmentConsent?>
}

interface ICommandRepository {
    suspend fun sendFlashlightCommand(deviceId: String, parentUid: String, enabled: Boolean): AppResult<Unit>
    suspend fun sendRefreshCommand(deviceId: String, parentUid: String): AppResult<Unit>
    fun observeFlashlightCommand(deviceId: String): Flow<RemoteFlashlightCommand?>
    fun observeRefreshCommand(deviceId: String): Flow<RemoteRefreshCommand?>
    suspend fun acknowledgeFlashlightCommand(deviceId: String, result: CommandExecutionResult): AppResult<Unit>
    suspend fun acknowledgeRefreshCommand(deviceId: String, result: CommandExecutionResult): AppResult<Unit>
}

interface ILocationRepository {
    suspend fun getCurrentLocation(): AppResult<Pair<Double, Double>?>
}

interface INetworkRepository {
    fun getConnectedWifiSsid(): String?
    fun isConnected(): Boolean
}

interface IUsageStatsRepository {
    suspend fun getTodayAppUsage(): AppResult<List<AppUsageEntry>>
    suspend fun getTodayTotalScreenTimeMs(): Long
}

interface ICallLogRepository {
    suspend fun getRecentCalls(limit: Int = 10): AppResult<List<CallEntry>>
}

interface IMediaRepository {
    suspend fun getRecentMedia(limit: Int = 10): AppResult<List<MediaEntry>>
}

interface IDeviceDiagnosticsRepository {
    fun getBatteryLevel(): Int
    fun isDeviceCharging(): Boolean
    fun getDeviceMetadata(): DeviceMetadata
}

interface IFlashlightController {
    val isAvailable: Boolean
    val isTorchOn: Boolean
    suspend fun setTorch(enabled: Boolean): AppResult<Boolean>
}
