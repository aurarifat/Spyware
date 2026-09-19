package com.example.data.repository

import com.example.core.common.AppError
import com.example.core.common.AppResult
import com.example.core.common.SafeLogger
import com.example.core.firebase.FirebasePaths
import com.example.data.firebase.FirebaseRealtimeDataSource
import com.example.domain.model.CommandExecutionResult
import com.example.domain.model.RemoteFlashlightCommand
import com.example.domain.model.RemoteRefreshCommand
import com.example.domain.repository.ICommandRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class CommandRepositoryImpl(
    private val firebaseDataSource: FirebaseRealtimeDataSource
) : ICommandRepository {

    override suspend fun sendFlashlightCommand(
        deviceId: String,
        parentUid: String,
        enabled: Boolean
    ): AppResult<Unit> {
        if (parentUid.isBlank()) {
            return AppResult.Error(AppError.AuthenticationRequired)
        }
        val payload = mapOf(
            "enabled" to enabled,
            "requestedAt" to System.currentTimeMillis(),
            "requestedBy" to parentUid
        )
        SafeLogger.i(TAG, "Parent $parentUid sending Flashlight ($enabled) to $deviceId")
        return firebaseDataSource.sendCommand(deviceId, FirebasePaths.CMD_FLASHLIGHT, payload)
    }

    override suspend fun sendRefreshCommand(
        deviceId: String,
        parentUid: String
    ): AppResult<Unit> {
        if (parentUid.isBlank()) {
            return AppResult.Error(AppError.AuthenticationRequired)
        }
        val payload = mapOf(
            "requested" to true,
            "requestedAt" to System.currentTimeMillis(),
            "requestedBy" to parentUid
        )
        SafeLogger.i(TAG, "Parent $parentUid sending RefreshStatus to $deviceId")
        return firebaseDataSource.sendCommand(deviceId, FirebasePaths.CMD_REFRESH_STATUS, payload)
    }

    override fun observeFlashlightCommand(deviceId: String): Flow<RemoteFlashlightCommand?> {
        return firebaseDataSource.observeDeviceFullState(deviceId).map { it?.flashlightCommand }
    }

    override fun observeRefreshCommand(deviceId: String): Flow<RemoteRefreshCommand?> {
        return firebaseDataSource.observeDeviceFullState(deviceId).map { it?.refreshCommand }
    }

    override suspend fun acknowledgeFlashlightCommand(
        deviceId: String,
        result: CommandExecutionResult
    ): AppResult<Unit> {
        return firebaseDataSource.acknowledgeCommand(deviceId, FirebasePaths.CMD_FLASHLIGHT, result)
    }

    override suspend fun acknowledgeRefreshCommand(
        deviceId: String,
        result: CommandExecutionResult
    ): AppResult<Unit> {
        return firebaseDataSource.acknowledgeCommand(deviceId, FirebasePaths.CMD_REFRESH_STATUS, result)
    }

    companion object {
        private const val TAG = "CommandRepository"
    }
}
