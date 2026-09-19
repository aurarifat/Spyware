package com.example.core.permissions

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Robust, Android-version-aware permission manager.
 * Respects platform sandboxing and special access mechanisms.
 */
class PermissionManager(private val context: Context) {

    fun isPermissionGranted(type: PermissionType): Boolean {
        return when (type) {
            PermissionType.USAGE_STATS -> hasUsageStatsPermission()
            PermissionType.LOCATION -> hasLocationPermission()
            PermissionType.CALL_LOG -> hasCallLogPermission()
            PermissionType.MEDIA_METADATA -> hasMediaPermission()
            PermissionType.CAMERA_FLASH -> hasCameraPermission()
            PermissionType.NOTIFICATIONS -> hasNotificationPermission()
        }
    }

    fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    fun hasCallLogPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasMediaPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * Checks if the special PACKAGE_USAGE_STATS permission is granted via [AppOpsManager].
     * Note: This is an AppOps operation and CANNOT be requested via standard runtime dialog.
     */
    fun hasUsageStatsPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Creates an Intent to navigate the user directly to the system Usage Access Settings.
     */
    fun createUsageAccessSettingsIntent(): Intent {
        return Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            // Optional data URI pointing to current app package
            data = Uri.parse("package:${context.packageName}")
        }
    }

    /**
     * Creates an Intent to open the Application Details Settings page in system settings.
     */
    fun createAppSettingsIntent(): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            data = Uri.fromParts("package", context.packageName, null)
        }
    }

    /**
     * Evaluates full permission snapshot for reporting and synchronization to Firebase.
     */
    fun getPermissionMap(): Map<String, Boolean> {
        return mapOf(
            "location" to hasLocationPermission(),
            "usageAccess" to hasUsageStatsPermission(),
            "callLog" to hasCallLogPermission(),
            "media" to hasMediaPermission(),
            "camera" to hasCameraPermission(),
            "notifications" to hasNotificationPermission()
        )
    }
}
