package com.example.data.p2p

import android.content.Context
import com.example.core.common.AppError
import com.example.core.common.AppResult
import com.example.core.common.SafeLogger
import com.example.domain.model.AppUsageEntry
import com.example.domain.model.CallEntry
import com.example.domain.model.ChildDeviceFullState
import com.example.domain.model.DeviceMetadata
import com.example.domain.model.DeviceStatus
import com.example.domain.model.EnrollmentConsent
import com.example.domain.model.MediaEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class DiscoveredChildDevice(
    val deviceId: String,
    val deviceName: String,
    val ipAddress: String,
    val port: Int
) {
    val address: String get() = "$ipAddress:$port"
}

typealias P2PDeviceInfo = DiscoveredChildDevice

/**
 * Client used by Parent mode to discover, link, monitor, and command Child devices over local Wi-Fi / P2P.
 */
class ParentP2PClient(private val context: Context) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // Cache of deviceId to IP Address
    private val deviceAddressMap = ConcurrentHashMap<String, String>()

    fun registerDeviceAddress(deviceId: String, ipAddress: String) {
        val cleanIp = ipAddress.trim().removePrefix("http://").removePrefix("https://").substringBefore("/")
        deviceAddressMap[deviceId] = cleanIp
        SafeLogger.i(TAG, "Registered child address: $deviceId -> $cleanIp")
    }

    fun setDeviceAddress(deviceId: String, ipAddress: String) {
        registerDeviceAddress(deviceId, ipAddress)
    }

    fun getDeviceAddress(deviceId: String): String? {
        return deviceAddressMap[deviceId]
    }

    /**
     * Broadcasts UDP packet on local subnet to find online Child devices automatically.
     */
    suspend fun discoverChildDevices(timeoutMs: Long = 3000): List<DiscoveredChildDevice> = withContext(Dispatchers.IO) {
        val discovered = mutableListOf<DiscoveredChildDevice>()
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket().apply {
                broadcast = true
                soTimeout = timeoutMs.toInt()
            }

            val query = "DISCOVER_FAMILY_CHILD".toByteArray(StandardCharsets.UTF_8)
            val broadcastAddress = InetAddress.getByName("255.255.255.255")
            val packet = DatagramPacket(query, query.size, broadcastAddress, P2PNetworkUtils.DISCOVERY_PORT)
            socket.send(packet)

            val buffer = ByteArray(1024)
            val startTime = System.currentTimeMillis()

            while (System.currentTimeMillis() - startTime < timeoutMs) {
                try {
                    val responsePacket = DatagramPacket(buffer, buffer.size)
                    socket.receive(responsePacket)

                    val response = String(responsePacket.data, 0, responsePacket.length, StandardCharsets.UTF_8).trim()
                    if (response.startsWith("CHILD_ACK|")) {
                        val parts = response.split("|")
                        if (parts.size >= 4) {
                            val deviceId = parts[1]
                            val deviceName = parts[2]
                            val ip = responsePacket.address.hostAddress ?: parts[3]
                            val port = parts.getOrNull(4)?.toIntOrNull() ?: P2PNetworkUtils.DEFAULT_PORT

                            registerDeviceAddress(deviceId, "$ip:$port")
                            discovered.add(DiscoveredChildDevice(deviceId = deviceId, deviceName = deviceName, ipAddress = ip, port = port))
                        }
                    }
                } catch (e: Exception) {
                    // Socket timeout reached
                    break
                }
            }
        } catch (e: Exception) {
            SafeLogger.w(TAG, "Discovery broadcast warning: ${e.message}")
        } finally {
            try {
                socket?.close()
            } catch (e: Exception) {
                // Ignore
            }
        }
        discovered.distinctBy { it.deviceId }
    }

    /**
     * Links a parent account to a child device over HTTP.
     */
    suspend fun linkChildDevice(
        hostAndPort: String,
        parentUid: String,
        parentDisplayName: String
    ): AppResult<String> = withContext(Dispatchers.IO) {
        try {
            val url = buildUrl(hostAndPort, "/api/link")
            val bodyJson = JSONObject().apply {
                put("parentUid", parentUid)
                put("parentDisplayName", parentDisplayName)
            }.toString()

            val request = Request.Builder()
                .url(url)
                .post(bodyJson.toRequestBody(jsonMediaType))
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: "{}"
                    val json = JSONObject(responseBody)
                    val deviceId = json.optString("deviceId", "")
                    if (deviceId.isNotBlank()) {
                        registerDeviceAddress(deviceId, hostAndPort)
                    }
                    AppResult.Success(deviceId)
                } else {
                    AppResult.Error(AppError.UnknownError("Link failed with HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            SafeLogger.e(TAG, "Error linking child over P2P: ${e.message}")
            AppResult.Error(AppError.UnknownError("Cannot reach child device at $hostAndPort: ${e.message}"))
        }
    }

    /**
     * Fetches current full state of child device over local network.
     */
    suspend fun fetchChildStatus(hostAndPort: String): AppResult<ChildDeviceFullState> = withContext(Dispatchers.IO) {
        try {
            val url = buildUrl(hostAndPort, "/api/status")
            val request = Request.Builder().url(url).get().build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@withContext AppResult.Error(AppError.UnknownError("Empty response"))
                    val json = JSONObject(body)

                    val deviceId = json.optString("deviceId", "")
                    val deviceName = json.optString("deviceName", "Child Device")
                    val parentUid = json.optString("parentUid", "")

                    val statusJson = json.optJSONObject("status") ?: JSONObject()
                    val appsArray = statusJson.optJSONArray("appUsage")
                    val appUsageList = mutableListOf<AppUsageEntry>()
                    if (appsArray != null) {
                        for (i in 0 until appsArray.length()) {
                            val appObj = appsArray.getJSONObject(i)
                            appUsageList.add(
                                AppUsageEntry(
                                    packageName = appObj.optString("packageName"),
                                    appName = appObj.optString("appName"),
                                    foregroundTimeMs = appObj.optLong("foregroundTimeMs"),
                                    foregroundTimeFormatted = appObj.optString("foregroundTimeFormatted")
                                )
                            )
                        }
                    }

                    val status = DeviceStatus(
                        lastSeen = statusJson.optLong("lastSeen", System.currentTimeMillis()),
                        batteryLevel = statusJson.optInt("batteryLevel", 0),
                        isCharging = statusJson.optBoolean("isCharging", false),
                        wifiSsid = statusJson.optString("wifiSsid", "Wi-Fi"),
                        latitude = if (statusJson.has("latitude")) statusJson.optDouble("latitude") else null,
                        longitude = if (statusJson.has("longitude")) statusJson.optDouble("longitude") else null,
                        totalScreenTimeMs = statusJson.optLong("totalScreenTimeMs", 0L),
                        totalScreenTimeFormatted = statusJson.optString("totalScreenTimeFormatted", "0m"),
                        appUsage = appUsageList
                    )

                    val callsArray = json.optJSONArray("calls")
                    val callsList = mutableListOf<CallEntry>()
                    if (callsArray != null) {
                        for (i in 0 until callsArray.length()) {
                            val cObj = callsArray.getJSONObject(i)
                            callsList.add(
                                CallEntry(
                                    callId = cObj.optString("callId"),
                                    name = cObj.optString("name"),
                                    number = cObj.optString("number"),
                                    type = cObj.optString("type"),
                                    timestamp = cObj.optLong("timestamp")
                                )
                            )
                        }
                    }

                    val mediaArray = json.optJSONArray("media")
                    val mediaList = mutableListOf<MediaEntry>()
                    if (mediaArray != null) {
                        for (i in 0 until mediaArray.length()) {
                            val mObj = mediaArray.getJSONObject(i)
                            mediaList.add(
                                MediaEntry(
                                    mediaId = mObj.optString("mediaId"),
                                    displayName = mObj.optString("displayName"),
                                    relativePath = mObj.optString("relativePath"),
                                    contentUri = mObj.optString("contentUri"),
                                    thumbnailBase64 = mObj.optString("thumbnailBase64"),
                                    dateAdded = mObj.optLong("dateAdded")
                                )
                            )
                        }
                    }

                    val metaJson = json.optJSONObject("metadata")
                    val metadata = if (metaJson != null) {
                        DeviceMetadata(
                            model = metaJson.optString("model"),
                            manufacturer = metaJson.optString("manufacturer"),
                            androidVersion = metaJson.optString("androidVersion"),
                            appVersion = metaJson.optString("appVersion")
                        )
                    } else DeviceMetadata()

                    val fullState = ChildDeviceFullState(
                        deviceId = deviceId,
                        parentUid = parentUid,
                        status = status,
                        calls = callsList,
                        media = mediaList,
                        metadata = metadata,
                        enrollment = EnrollmentConsent(
                            enabled = json.optBoolean("isConsentGiven", true),
                            parentUid = parentUid
                        )
                    )

                    if (deviceId.isNotBlank()) {
                        registerDeviceAddress(deviceId, hostAndPort)
                    }

                    AppResult.Success(fullState)
                } else {
                    AppResult.Error(AppError.UnknownError("HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            AppResult.Error(AppError.UnknownError("Failed fetching status: ${e.message}"))
        }
    }

    /**
     * Sends a command (e.g. flashlight) to child device over local network.
     */
    suspend fun sendCommand(
        hostAndPort: String,
        commandName: String,
        params: Map<String, Any> = emptyMap()
    ): AppResult<Boolean> = withContext(Dispatchers.IO) {
        try {
            val url = buildUrl(hostAndPort, "/api/command")
            val json = JSONObject().apply {
                put("command", commandName)
                params.forEach { (k, v) -> put(k, v) }
            }

            val request = Request.Builder()
                .url(url)
                .post(json.toString().toRequestBody(jsonMediaType))
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    AppResult.Success(true)
                } else {
                    AppResult.Error(AppError.UnknownError("Command failed with HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            AppResult.Error(AppError.UnknownError("Error sending command: ${e.message}"))
        }
    }

    /**
     * Controls the child's remote camera (start, stop, switch lens, torch).
     */
    suspend fun sendCameraControl(
        hostAndPort: String,
        action: String,
        params: Map<String, Any> = emptyMap()
    ): AppResult<JSONObject> = withContext(Dispatchers.IO) {
        try {
            val url = buildUrl(hostAndPort, "/api/command/camera?action=$action")
            val json = JSONObject().apply {
                put("action", action)
                params.forEach { (k, v) -> put(k, v) }
            }

            val request = Request.Builder()
                .url(url)
                .post(json.toString().toRequestBody(jsonMediaType))
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: "{}"
                    AppResult.Success(JSONObject(body))
                } else {
                    AppResult.Error(AppError.UnknownError("Camera control failed: HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            AppResult.Error(AppError.UnknownError("Camera control error: ${e.message}"))
        }
    }

    /**
     * Fetches a single latest JPEG frame from the child camera.
     */
    suspend fun fetchCameraFrame(hostAndPort: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val url = buildUrl(hostAndPort, "/api/camera/frame")
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.body?.bytes()
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun buildUrl(hostAndPort: String, path: String): String {
        val cleanHost = hostAndPort.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
        val formattedHost = if (!cleanHost.contains(":")) "$cleanHost:${P2PNetworkUtils.DEFAULT_PORT}" else cleanHost
        return "http://$formattedHost$path"
    }

    companion object {
        private const val TAG = "ParentP2PClient"

        @Volatile
        private var instance: ParentP2PClient? = null

        fun getInstance(context: Context): ParentP2PClient {
            return instance ?: synchronized(this) {
                instance ?: ParentP2PClient(context.applicationContext).also { instance = it }
            }
        }
    }
}
