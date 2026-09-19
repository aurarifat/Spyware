package com.example.data.p2p

import android.content.Context
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale

object P2PNetworkUtils {

    const val DEFAULT_PORT = 8888
    const val DISCOVERY_PORT = 8889

    /**
     * Resolves the primary local IPv4 address (e.g. 192.168.x.x) on Wi-Fi or hotspot.
     */
    fun getLocalIpAddress(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                // Ignore loopback or inactive interfaces
                if (intf.isLoopback || !intf.isUp) continue

                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress ?: continue
                        // Prioritize local network ranges (192.168.x.x, 10.x.x.x, 172.16-31.x.x)
                        if (host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172.")) {
                            return host
                        }
                    }
                }
            }

            // Fallback: check any non-loopback IPv4
            for (intf in interfaces) {
                for (addr in Collections.list(intf.inetAddresses)) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (e: Exception) {
            // Ignored
        }
        return "127.0.0.1"
    }

    /**
     * Generates a user-friendly 6-digit short pairing code from device ID.
     */
    fun generateShortPairingCode(deviceId: String): String {
        val hash = kotlin.math.abs(deviceId.hashCode()) % 1000000
        return String.format(Locale.US, "%06d", hash)
    }
}
