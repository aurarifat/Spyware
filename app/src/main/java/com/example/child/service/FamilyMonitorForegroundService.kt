package com.example.child.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.example.FamilyWellbeingApplication
import com.example.core.common.SafeLogger
import com.example.core.notification.NotificationHelper
import com.example.domain.model.CommandExecutionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Transparent Foreground Service for Child Device Digital Wellbeing Synchronization.
 *
 * Android Platform Compliance & Background Execution Notice:
 * 1. Android strictly restricts background execution (Doze mode, background limits, app standbys).
 * 2. This service operates in the FOREGROUND with a mandatory ongoing notification visible to the user at all times.
 * 3. Android 14+ (API 34) requires [ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC].
 * 4. Synchronization interval is throttled (10 minutes) to prevent battery drain while respecting OS restrictions.
 * 5. Coroutine structured concurrency ensures graceful shutdown without memory or thread leaks.
 */
class FamilyMonitorForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var syncJob: Job? = null
    private var commandObserverJob: Job? = null

    private val appContainer by lazy {
        (application as FamilyWellbeingApplication).container
    }

    private var lastFlashlightTimestamp: Long = 0L
    private var lastRefreshTimestamp: Long = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        SafeLogger.i(TAG, "FamilyMonitorForegroundService onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            SafeLogger.i(TAG, "Received STOP action for foreground service")
            stopSelf()
            return START_NOT_STICKY
        }

        val deviceId = intent?.getStringExtra(EXTRA_DEVICE_ID) ?: appContainer.deviceIdManager.getOrCreateDeviceId()
        val parentName = intent?.getStringExtra(EXTRA_PARENT_NAME) ?: "Linked Parent"

        startForegroundNotification(parentName)
        startPeriodicSyncLoop(deviceId, parentName)
        startCommandObservation(deviceId)

        return START_STICKY
    }

    private fun startForegroundNotification(parentName: String) {
        val notification = appContainer.notificationHelper.buildForegroundNotification(
            parentName = parentName,
            lastSyncFormatted = "Starting up..."
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            startForeground(NotificationHelper.NOTIFICATION_ID, notification, serviceType)
        } else {
            startForeground(NotificationHelper.NOTIFICATION_ID, notification)
        }
    }

    private fun startPeriodicSyncLoop(deviceId: String, parentName: String) {
        syncJob?.cancel()
        syncJob = serviceScope.launch {
            while (isActive) {
                try {
                    val result = appContainer.syncChildStatusUseCase(deviceId)
                    val timeStr = java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault()).format(java.util.Date())
                    appContainer.notificationHelper.updateForegroundNotification(
                        parentName = parentName,
                        lastSyncFormatted = timeStr
                    )
                    SafeLogger.d(TAG, "Periodic sync completed at $timeStr")
                } catch (e: Exception) {
                    SafeLogger.e(TAG, "Error in periodic sync cycle: ${e.message}")
                }
                // Periodic sync interval: 10 minutes (respects battery & network policy)
                delay(SYNC_INTERVAL_MS)
            }
        }
    }

    private fun startCommandObservation(deviceId: String) {
        commandObserverJob?.cancel()
        commandObserverJob = serviceScope.launch {
            // Observe Flashlight commands
            launch {
                appContainer.commandRepository.observeFlashlightCommand(deviceId).collectLatest { cmd ->
                    if (cmd != null && cmd.requestedAt > lastFlashlightTimestamp) {
                        lastFlashlightTimestamp = cmd.requestedAt
                        SafeLogger.i(TAG, "Executing received flashlight command: ${cmd.enabled}")
                        appContainer.executeFlashlightCommandUseCase(deviceId, cmd.enabled)
                    }
                }
            }

            // Observe Refresh Status commands
            launch {
                appContainer.commandRepository.observeRefreshCommand(deviceId).collectLatest { cmd ->
                    if (cmd != null && cmd.requested && cmd.requestedAt > lastRefreshTimestamp) {
                        lastRefreshTimestamp = cmd.requestedAt
                        SafeLogger.i(TAG, "Executing received refresh status command from parent ${cmd.requestedBy}")

                        val syncRes = appContainer.syncChildStatusUseCase(deviceId)
                        val ack = CommandExecutionResult(
                            commandName = "refreshStatus",
                            success = syncRes.isSuccess,
                            executedAt = System.currentTimeMillis(),
                            error = syncRes.errorOrNull()?.toString()
                        )
                        appContainer.commandRepository.acknowledgeRefreshCommand(deviceId, ack)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        SafeLogger.i(TAG, "FamilyMonitorForegroundService onDestroy")
        syncJob?.cancel()
        commandObserverJob?.cancel()
        serviceScope.cancel()

        // Turn off torch if it was active
        if (appContainer.flashlightController.isTorchOn) {
            CoroutineScope(Dispatchers.IO).launch {
                appContainer.flashlightController.setTorch(false)
            }
        }
    }

    companion object {
        private const val TAG = "FamilyMonitorService"
        const val ACTION_STOP_SERVICE = "com.example.action.STOP_SUPERVISION"
        const val EXTRA_DEVICE_ID = "extra_device_id"
        const val EXTRA_PARENT_NAME = "extra_parent_name"
        private const val SYNC_INTERVAL_MS = 10 * 60 * 1000L // 10 minutes

        fun startService(context: Context, deviceId: String, parentName: String) {
            val intent = Intent(context, FamilyMonitorForegroundService::class.java).apply {
                putExtra(EXTRA_DEVICE_ID, deviceId)
                putExtra(EXTRA_PARENT_NAME, parentName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, FamilyMonitorForegroundService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            context.startService(intent)
        }
    }
}
