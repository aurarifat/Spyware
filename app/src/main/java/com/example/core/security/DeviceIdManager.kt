package com.example.core.security

import android.content.Context
import android.content.SharedPreferences
import com.example.core.common.SafeLogger
import java.util.UUID

/**
 * Manages privacy-preserving, application-scoped device identity.
 *
 * Security & Privacy Architecture:
 * 1. Generation: Generates a cryptographically strong random UUID (v4) using [java.util.UUID.randomUUID].
 * 2. Non-Fingerprinting: Does NOT query IMEI, MAC address, ANDROID_ID, or DRM client ID, complying strictly with
 *    Google Play privacy policies against persistent hardware tracking.
 * 3. Persistence: Stored inside app-private [SharedPreferences] in [Context.MODE_PRIVATE].
 * 4. Reinstall Behavior: The identifier is regenerated upon app reinstallation or clearing app storage data.
 *    This ensures the user retains ultimate sovereignty to reset their device identity at any time.
 * 5. Scope: Strictly scoped to this application installation; cannot be correlated by other installed applications.
 */
class DeviceIdManager(private val context: Context) {

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Synchronized
    fun getOrCreateDeviceId(): String {
        var deviceId = prefs.getString(KEY_DEVICE_ID, null)
        if (deviceId.isNullOrBlank()) {
            val newId = "dev_" + UUID.randomUUID().toString().replace("-", "").take(16)
            prefs.edit().putString(KEY_DEVICE_ID, newId).apply()
            SafeLogger.i(TAG, "Generated fresh privacy-safe device ID: ${SafeLogger.maskId(newId)}")
            deviceId = newId
        }
        return deviceId
    }

    /**
     * Explicit reset mechanism for unenrollment or data wipe.
     */
    @Synchronized
    fun resetDeviceId(): String {
        prefs.edit().remove(KEY_DEVICE_ID).apply()
        return getOrCreateDeviceId()
    }

    companion object {
        private const val TAG = "DeviceIdManager"
        private const val PREFS_NAME = "family_device_identity_prefs"
        private const val KEY_DEVICE_ID = "app_scoped_device_id"
    }
}
