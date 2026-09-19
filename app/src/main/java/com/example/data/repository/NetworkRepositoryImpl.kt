package com.example.data.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.core.common.SafeLogger

/**
 * Android Network & Wi-Fi Repository.
 *
 * Privacy & Version Notice:
 * - Starting in Android 8.1 (API 27) and strictly enforced in Android 10+ (API 29), retrieving the connected Wi-Fi SSID
 *   requires ACCESS_FINE_LOCATION permission and location services to be enabled. Without location permission, the system
 *   returns "<unknown ssid>".
 * - If location is not permitted or unavailable, this repository gracefully returns "Wi-Fi Connected (SSID Hidden)" or "Mobile Data".
 */
class NetworkRepositoryImpl(private val context: Context) : com.example.domain.repository.INetworkRepository {

    private val connectivityManager by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }

    private val wifiManager by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    }

    override fun isConnected(): Boolean {
        val cm = connectivityManager ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun getConnectedWifiSsid(): String? {
        val cm = connectivityManager ?: return null
        val activeNetwork = cm.activeNetwork ?: return "Offline"
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return "Disconnected"

        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            return "VPN Connection"
        }

        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            return "Mobile Data (Cellular)"
        }

        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            // Check location permission requirement for SSID resolution
            val hasFineLoc = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

            if (!hasFineLoc) {
                return "Wi-Fi Connected"
            }

            return try {
                val wifiInfo: WifiInfo? = wifiManager?.connectionInfo
                val ssid = wifiInfo?.ssid
                if (ssid.isNullOrBlank() || ssid == "<unknown ssid>" || ssid == "\"<unknown ssid>\"") {
                    "Wi-Fi (SSID Restricted)"
                } else {
                    ssid.removeSurrounding("\"")
                }
            } catch (e: Exception) {
                SafeLogger.w(TAG, "Wi-Fi SSID lookup failed: ${e.message}")
                "Wi-Fi Connected"
            }
        }

        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            return "Ethernet"
        }

        return "Connected"
    }

    companion object {
        private const val TAG = "NetworkRepository"
    }
}
