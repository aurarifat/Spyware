package com.example.child.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.FamilyWellbeingApplication
import com.example.core.common.SafeLogger

/**
 * Opportunistic background wellbeing sync worker using WorkManager.
 * Ensures data freshness even across system restarts or low-power cycles.
 */
class SyncWellbeingWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        SafeLogger.i(TAG, "SyncWellbeingWorker starting background task")
        val app = applicationContext as? FamilyWellbeingApplication ?: return Result.failure()
        val container = app.container

        val deviceId = container.deviceIdManager.getOrCreateDeviceId()
        val isConsented = container.localPrefs.isConsentGiven()

        if (!isConsented) {
            SafeLogger.i(TAG, "Consent not given; skipping WorkManager execution")
            return Result.success()
        }

        return try {
            val syncResult = container.syncChildStatusUseCase(deviceId)
            if (syncResult.isSuccess) {
                SafeLogger.i(TAG, "SyncWellbeingWorker finished successfully")
                Result.success()
            } else {
                SafeLogger.w(TAG, "SyncWellbeingWorker failed: ${syncResult.errorOrNull()}")
                Result.retry()
            }
        } catch (e: Exception) {
            SafeLogger.e(TAG, "Exception during SyncWellbeingWorker: ${e.message}")
            Result.failure()
        }
    }

    companion object {
        private const val TAG = "SyncWellbeingWorker"
        const val UNIQUE_WORK_NAME = "FamilyWellbeingPeriodicWork"
    }
}
