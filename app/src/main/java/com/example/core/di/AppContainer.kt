package com.example.core.di

import android.content.Context
import com.example.core.notification.NotificationHelper
import com.example.core.permissions.PermissionManager
import com.example.core.security.DeviceIdManager
import com.example.data.datasource.LocalPreferencesDataSource
import com.example.data.firebase.FirebaseRealtimeDataSource
import com.example.data.repository.AuthRepositoryImpl
import com.example.data.repository.CallLogRepositoryImpl
import com.example.data.repository.CommandRepositoryImpl
import com.example.data.repository.DeviceDiagnosticsRepositoryImpl
import com.example.data.repository.DeviceRepositoryImpl
import com.example.data.repository.FlashlightControllerImpl
import com.example.data.repository.LocationRepositoryImpl
import com.example.data.repository.MediaRepositoryImpl
import com.example.data.repository.NetworkRepositoryImpl
import com.example.data.repository.UsageStatsRepositoryImpl
import com.example.domain.repository.IAuthRepository
import com.example.domain.repository.ICallLogRepository
import com.example.domain.repository.ICommandRepository
import com.example.domain.repository.IDeviceDiagnosticsRepository
import com.example.domain.repository.IDeviceRepository
import com.example.domain.repository.IFlashlightController
import com.example.domain.repository.ILocationRepository
import com.example.domain.repository.IMediaRepository
import com.example.domain.repository.INetworkRepository
import com.example.domain.repository.IUsageStatsRepository
import com.example.domain.usecase.EnrollChildUseCase
import com.example.domain.usecase.ExecuteFlashlightCommandUseCase
import com.example.domain.usecase.SyncChildStatusUseCase

interface AppContainer {
    val localPrefs: LocalPreferencesDataSource
    val deviceIdManager: DeviceIdManager
    val permissionManager: PermissionManager
    val notificationHelper: NotificationHelper
    val authRepository: IAuthRepository
    val deviceRepository: IDeviceRepository
    val commandRepository: ICommandRepository
    val locationRepository: ILocationRepository
    val networkRepository: INetworkRepository
    val usageStatsRepository: IUsageStatsRepository
    val callLogRepository: ICallLogRepository
    val mediaRepository: IMediaRepository
    val diagnosticsRepository: IDeviceDiagnosticsRepository
    val flashlightController: IFlashlightController
    val syncChildStatusUseCase: SyncChildStatusUseCase
    val enrollChildUseCase: EnrollChildUseCase
    val executeFlashlightCommandUseCase: ExecuteFlashlightCommandUseCase
}

class DefaultAppContainer(private val context: Context) : AppContainer {

    override val localPrefs: LocalPreferencesDataSource by lazy {
        LocalPreferencesDataSource(context)
    }

    override val deviceIdManager: DeviceIdManager by lazy {
        DeviceIdManager(context)
    }

    override val permissionManager: PermissionManager by lazy {
        PermissionManager(context)
    }

    override val notificationHelper: NotificationHelper by lazy {
        NotificationHelper(context)
    }

    private val firebaseDataSource: FirebaseRealtimeDataSource by lazy {
        FirebaseRealtimeDataSource(context)
    }

    override val authRepository: IAuthRepository by lazy {
        AuthRepositoryImpl(context)
    }

    override val deviceRepository: IDeviceRepository by lazy {
        DeviceRepositoryImpl(firebaseDataSource)
    }

    override val commandRepository: ICommandRepository by lazy {
        CommandRepositoryImpl(firebaseDataSource)
    }

    override val locationRepository: ILocationRepository by lazy {
        LocationRepositoryImpl(context, permissionManager)
    }

    override val networkRepository: INetworkRepository by lazy {
        NetworkRepositoryImpl(context)
    }

    override val usageStatsRepository: IUsageStatsRepository by lazy {
        UsageStatsRepositoryImpl(context, permissionManager)
    }

    override val callLogRepository: ICallLogRepository by lazy {
        CallLogRepositoryImpl(context, permissionManager)
    }

    override val mediaRepository: IMediaRepository by lazy {
        MediaRepositoryImpl(context, permissionManager)
    }

    override val diagnosticsRepository: IDeviceDiagnosticsRepository by lazy {
        DeviceDiagnosticsRepositoryImpl(context)
    }

    override val flashlightController: IFlashlightController by lazy {
        FlashlightControllerImpl(context)
    }

    override val syncChildStatusUseCase: SyncChildStatusUseCase by lazy {
        SyncChildStatusUseCase(
            deviceRepository = deviceRepository,
            diagnosticsRepository = diagnosticsRepository,
            networkRepository = networkRepository,
            usageStatsRepository = usageStatsRepository,
            locationRepository = locationRepository,
            callLogRepository = callLogRepository,
            mediaRepository = mediaRepository,
            permissionManager = permissionManager
        )
    }

    override val enrollChildUseCase: EnrollChildUseCase by lazy {
        EnrollChildUseCase(deviceRepository)
    }

    override val executeFlashlightCommandUseCase: ExecuteFlashlightCommandUseCase by lazy {
        ExecuteFlashlightCommandUseCase(flashlightController, commandRepository)
    }
}
