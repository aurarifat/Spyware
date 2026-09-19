package com.example

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.child.worker.SyncWellbeingWorker
import com.example.core.common.SafeLogger
import com.example.core.di.AppContainer
import com.example.core.di.DefaultAppContainer
import java.util.concurrent.TimeUnit

class FamilyWellbeingApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        SafeLogger.i(TAG, "FamilyWellbeingApplication initializing")

        // Initialize Dependency Injection Container
        container = DefaultAppContainer(this)

        // Pre-create notification channel for transparency
        container.notificationHelper

        // Enqueue periodic WorkManager synchronization (15-minute interval)
        try {
            val periodicWork = PeriodicWorkRequestBuilder<SyncWellbeingWorker>(
                15, TimeUnit.MINUTES
            ).build()

            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                SyncWellbeingWorker.UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicWork
            )
            SafeLogger.d(TAG, "WorkManager periodic wellbeing sync scheduled")
        } catch (e: Exception) {
            SafeLogger.w(TAG, "WorkManager schedule warning: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "FamilyApp"
    }
}
