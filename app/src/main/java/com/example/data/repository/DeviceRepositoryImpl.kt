package com.example.data.repository

import com.example.core.common.AppResult
import com.example.data.firebase.FirebaseRealtimeDataSource
import com.example.domain.model.CallEntry
import com.example.domain.model.ChildDeviceFullState
import com.example.domain.model.DeviceMetadata
import com.example.domain.model.DeviceStatus
import com.example.domain.model.EnrollmentConsent
import com.example.domain.model.LinkedChildDevice
import com.example.domain.model.MediaEntry
import com.example.domain.repository.IDeviceRepository
import kotlinx.coroutines.flow.Flow

class DeviceRepositoryImpl(
    private val firebaseDataSource: FirebaseRealtimeDataSource
) : IDeviceRepository {

    override suspend fun saveEnrollment(deviceId: String, consent: EnrollmentConsent): AppResult<Unit> {
        return firebaseDataSource.saveEnrollment(deviceId, consent)
    }

    override suspend fun unlinkDevice(parentUid: String, deviceId: String): AppResult<Unit> {
        return firebaseDataSource.unlinkDevice(parentUid, deviceId)
    }

    override suspend fun syncStatus(deviceId: String, status: DeviceStatus): AppResult<Unit> {
        return firebaseDataSource.syncStatus(deviceId, status)
    }

    override suspend fun syncMetadata(deviceId: String, metadata: DeviceMetadata): AppResult<Unit> {
        return firebaseDataSource.syncMetadata(deviceId, metadata)
    }

    override suspend fun syncPermissions(deviceId: String, permissions: Map<String, Boolean>): AppResult<Unit> {
        return firebaseDataSource.syncPermissions(deviceId, permissions)
    }

    override suspend fun syncCalls(deviceId: String, calls: List<CallEntry>): AppResult<Unit> {
        return firebaseDataSource.syncCalls(deviceId, calls)
    }

    override suspend fun syncMedia(deviceId: String, media: List<MediaEntry>): AppResult<Unit> {
        return firebaseDataSource.syncMedia(deviceId, media)
    }

    override fun observeDeviceFullState(deviceId: String): Flow<ChildDeviceFullState?> {
        return firebaseDataSource.observeDeviceFullState(deviceId)
    }

    override fun observeParentLinkedDevices(parentUid: String): Flow<List<LinkedChildDevice>> {
        return firebaseDataSource.observeParentLinkedDevices(parentUid)
    }

    override suspend fun linkChildToParent(parentUid: String, device: LinkedChildDevice): AppResult<Unit> {
        return firebaseDataSource.linkChildToParent(parentUid, device)
    }

    override suspend fun getEnrollment(deviceId: String): AppResult<EnrollmentConsent?> {
        // Will be populated via observeDeviceFullState or cached in dataSource
        return AppResult.Success(null)
    }

    override suspend fun updateLocalStateFromP2P(state: ChildDeviceFullState): AppResult<Unit> {
        firebaseDataSource.setLocalDeviceFullState(state)
        return AppResult.Success(Unit)
    }
}
