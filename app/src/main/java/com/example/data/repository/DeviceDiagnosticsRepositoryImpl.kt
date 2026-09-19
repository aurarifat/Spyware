package com.example.data.repository

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import com.example.domain.model.DeviceMetadata
import com.example.domain.repository.IDeviceDiagnosticsRepository

class DeviceDiagnosticsRepositoryImpl(
    private val context: Context
) : IDeviceDiagnosticsRepository {

    override fun getBatteryLevel(): Int {
        val batteryIntent: Intent? = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) {
            (level * 100 / scale)
        } else {
            50 // Safe fallback
        }
    }

    override fun isDeviceCharging(): Boolean {
        val batteryIntent: Intent? = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
    }

    override fun getDeviceMetadata(): DeviceMetadata {
        val appVersion = try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "1.0"
        } catch (e: Exception) {
            "1.0"
        }

        return DeviceMetadata(
            appVersion = appVersion,
            androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            model = Build.MODEL ?: "Unknown Device",
            manufacturer = (Build.MANUFACTURER ?: "Generic").replaceFirstChar { it.uppercase() },
            lastUpdated = System.currentTimeMillis()
        )
    }
}
