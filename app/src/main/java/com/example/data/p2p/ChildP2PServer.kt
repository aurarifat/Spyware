package com.example.data.p2p

import android.content.Context
import com.example.camera.ChildCameraStreamManager
import com.example.core.common.SafeLogger
import com.example.core.di.AppContainer
import com.example.domain.model.ChildDeviceFullState
import com.example.domain.model.EnrollmentConsent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * Embedded peer-to-peer server running on the Child's device.
 * Exposes real-time digital wellbeing metrics, command execution, and live camera streaming
 * over the local network / Wi-Fi to paired parent devices without requiring an external cloud server.
 */
class ChildP2PServer(
    private val context: Context,
    private val appContainer: AppContainer
) {

    private val serverScope = CoroutineScope(Dispatchers.IO + Job())
    private var serverSocket: ServerSocket? = null
    private var udpSocket: DatagramSocket? = null
    private var acceptJob: Job? = null
    private var udpJob: Job? = null

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _connectedParentIp = MutableStateFlow<String?>(null)
    val connectedParentIp: StateFlow<String?> = _connectedParentIp.asStateFlow()

    private val streamManager = ChildCameraStreamManager.getInstance()

    fun start() {
        if (_isRunning.value) return

        try {
            val port = P2PNetworkUtils.DEFAULT_PORT
            serverSocket = ServerSocket(port).apply {
                reuseAddress = true
            }
            _isRunning.value = true
            SafeLogger.i(TAG, "Child P2P Server started on port $port (IP: ${P2PNetworkUtils.getLocalIpAddress()})")

            startHttpServer()
            startUdpDiscoveryResponder()
        } catch (e: Exception) {
            SafeLogger.e(TAG, "Failed starting P2P server: ${e.message}", e)
            _isRunning.value = false
        }
    }

    private fun startHttpServer() {
        acceptJob = serverScope.launch {
            while (isActive && _isRunning.value) {
                try {
                    val clientSocket = serverSocket?.accept() ?: break
                    _connectedParentIp.value = clientSocket.inetAddress?.hostAddress
                    serverScope.launch {
                        handleClientConnection(clientSocket)
                    }
                } catch (e: Exception) {
                    if (isActive && _isRunning.value) {
                        SafeLogger.w(TAG, "Error accepting client connection: ${e.message}")
                    }
                }
            }
        }
    }

    private fun startUdpDiscoveryResponder() {
        udpJob = serverScope.launch {
            try {
                udpSocket = DatagramSocket(P2PNetworkUtils.DISCOVERY_PORT).apply {
                    reuseAddress = true
                    broadcast = true
                }
                val buffer = ByteArray(1024)

                while (isActive && _isRunning.value) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    udpSocket?.receive(packet)

                    val message = String(packet.data, 0, packet.length, StandardCharsets.UTF_8).trim()
                    if (message.startsWith("DISCOVER_FAMILY_CHILD")) {
                        val deviceId = appContainer.deviceIdManager.getOrCreateDeviceId()
                        val deviceName = appContainer.localPrefs.getChildDeviceName()
                        val response = "CHILD_ACK|$deviceId|$deviceName|${P2PNetworkUtils.getLocalIpAddress()}|${P2PNetworkUtils.DEFAULT_PORT}"
                        val responseBytes = response.toByteArray(StandardCharsets.UTF_8)
                        val replyPacket = DatagramPacket(
                            responseBytes,
                            responseBytes.size,
                            packet.address,
                            packet.port
                        )
                        udpSocket?.send(replyPacket)
                        SafeLogger.d(TAG, "Responded to discovery from ${packet.address.hostAddress}")
                    }
                }
            } catch (e: Exception) {
                if (isActive && _isRunning.value) {
                    SafeLogger.w(TAG, "UDP discovery responder stopped: ${e.message}")
                }
            }
        }
    }

    private suspend fun handleClientConnection(socket: Socket) {
        try {
            socket.soTimeout = 15000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val out = socket.getOutputStream()

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0]
            val path = parts[1]

            // Read HTTP headers
            var contentLength = 0
            var headerLine: String?
            while (reader.readLine().also { headerLine = it } != null) {
                if (headerLine.isNullOrBlank()) break
                if (headerLine!!.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = headerLine!!.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }

            // Read body if POST
            var body = ""
            if (method == "POST" && contentLength > 0) {
                val bodyChars = CharArray(contentLength)
                var totalRead = 0
                while (totalRead < contentLength) {
                    val read = reader.read(bodyChars, totalRead, contentLength - totalRead)
                    if (read == -1) break
                    totalRead += read
                }
                body = String(bodyChars, 0, totalRead)
            }

            when {
                path.startsWith("/api/status") -> handleGetStatus(out)
                path.startsWith("/api/link") && method == "POST" -> handlePostLink(body, out)
                path.startsWith("/api/command/camera") -> handleCameraControl(body, path, out)
                path.startsWith("/api/command") && method == "POST" -> handlePostCommand(body, out)
                path.startsWith("/api/camera/frame") -> handleGetCameraFrame(out)
                path.startsWith("/api/camera/stream") -> handleCameraMjpegStream(socket, out)
                else -> sendHttpResponse(out, 404, "application/json", "{\"error\":\"Not found\"}")
            }
        } catch (e: Exception) {
            SafeLogger.w(TAG, "Error handling client connection: ${e.message}")
        } finally {
            try {
                if (!socket.isClosed) socket.close()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    private suspend fun handleGetStatus(out: OutputStream) {
        val deviceId = appContainer.deviceIdManager.getOrCreateDeviceId()
        val deviceName = appContainer.localPrefs.getChildDeviceName()
        val linkedParentUid = appContainer.localPrefs.getLinkedParentUid() ?: ""
        val consentGiven = appContainer.localPrefs.isConsentGiven()

        // Gather latest status
        val statusRes = appContainer.syncChildStatusUseCase(deviceId)
        val status = if (statusRes is com.example.core.common.AppResult.Success) statusRes.data else com.example.domain.model.DeviceStatus()

        // Calls
        val callsRes = appContainer.callLogRepository.getRecentCalls(10)
        val calls = if (callsRes is com.example.core.common.AppResult.Success) callsRes.data else emptyList()

        // Media
        val mediaRes = appContainer.mediaRepository.getRecentMedia(8)
        val media = if (mediaRes is com.example.core.common.AppResult.Success) mediaRes.data else emptyList()

        // Metadata
        val metaRes = appContainer.diagnosticsRepository.collectDeviceMetadata()
        val meta = if (metaRes is com.example.core.common.AppResult.Success) metaRes.data else com.example.domain.model.DeviceMetadata()

        val json = JSONObject().apply {
            put("deviceId", deviceId)
            put("deviceName", deviceName)
            put("parentUid", linkedParentUid)
            put("isConsentGiven", consentGiven)

            put("status", JSONObject().apply {
                put("lastSeen", System.currentTimeMillis())
                put("batteryLevel", status.batteryLevel)
                put("isCharging", status.isCharging)
                put("wifiSsid", status.wifiSsid ?: "Wi-Fi Connected")
                put("totalScreenTimeFormatted", status.totalScreenTimeFormatted)
                put("totalScreenTimeMs", status.totalScreenTimeMs)
                if (status.latitude != null && status.longitude != null) {
                    put("latitude", status.latitude)
                    put("longitude", status.longitude)
                }

                val appsArray = JSONArray()
                status.appUsage.forEach { app ->
                    appsArray.put(JSONObject().apply {
                        put("packageName", app.packageName)
                        put("appName", app.appName)
                        put("foregroundTimeMs", app.foregroundTimeMs)
                        put("foregroundTimeFormatted", app.foregroundTimeFormatted)
                    })
                }
                put("appUsage", appsArray)
            })

            val callsArray = JSONArray()
            calls.forEach { call ->
                callsArray.put(JSONObject().apply {
                    put("callId", call.callId)
                    put("name", call.name)
                    put("number", call.number)
                    put("type", call.type)
                    put("timestamp", call.timestamp)
                })
            }
            put("calls", callsArray)

            val mediaArray = JSONArray()
            media.forEach { item ->
                mediaArray.put(JSONObject().apply {
                    put("mediaId", item.mediaId)
                    put("displayName", item.displayName)
                    put("relativePath", item.relativePath)
                    put("contentUri", item.contentUri)
                    put("thumbnailBase64", item.thumbnailBase64)
                    put("dateAdded", item.dateAdded)
                })
            }
            put("media", mediaArray)

            put("metadata", JSONObject().apply {
                put("model", meta.model)
                put("manufacturer", meta.manufacturer)
                put("androidVersion", meta.androidVersion)
                put("appVersion", meta.appVersion)
            })

            put("isCameraStreaming", streamManager.isStreamingActive.value)
            put("isFlashlightOn", appContainer.flashlightController.isFlashlightOn.value)
        }

        sendHttpResponse(out, 200, "application/json", json.toString())
    }

    private fun handlePostLink(body: String, out: OutputStream) {
        try {
            val json = JSONObject(body)
            val parentUid = json.optString("parentUid", "")
            val parentDisplayName = json.optString("parentDisplayName", "Parent")

            if (parentUid.isNotBlank()) {
                appContainer.localPrefs.setLinkedParentUid(parentUid)
                appContainer.localPrefs.setConsentGiven(true)

                val deviceId = appContainer.deviceIdManager.getOrCreateDeviceId()
                val consent = EnrollmentConsent(
                    enabled = true,
                    consentVersion = "1.0",
                    consentTimestamp = System.currentTimeMillis(),
                    parentUid = parentUid,
                    parentDisplayName = parentDisplayName
                )
                serverScope.launch {
                    appContainer.deviceRepository.saveEnrollment(deviceId, consent)
                }

                sendHttpResponse(out, 200, "application/json", "{\"status\":\"linked\",\"deviceId\":\"$deviceId\"}")
            } else {
                sendHttpResponse(out, 400, "application/json", "{\"error\":\"Missing parentUid\"}")
            }
        } catch (e: Exception) {
            sendHttpResponse(out, 400, "application/json", "{\"error\":\"${e.message}\"}")
        }
    }

    private suspend fun handlePostCommand(body: String, out: OutputStream) {
        try {
            val json = JSONObject(body)
            val command = json.optString("command", "")

            when (command) {
                "flashlight" -> {
                    val enabled = json.optBoolean("enabled", false)
                    appContainer.flashlightController.setFlashlight(enabled)
                    sendHttpResponse(out, 200, "application/json", "{\"success\":true,\"flashlight\":$enabled}")
                }
                "refresh" -> {
                    val deviceId = appContainer.deviceIdManager.getOrCreateDeviceId()
                    appContainer.syncChildStatusUseCase(deviceId)
                    sendHttpResponse(out, 200, "application/json", "{\"success\":true,\"refreshed\":true}")
                }
                else -> {
                    sendHttpResponse(out, 400, "application/json", "{\"error\":\"Unknown command: $command\"}")
                }
            }
        } catch (e: Exception) {
            sendHttpResponse(out, 400, "application/json", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun handleCameraControl(body: String, path: String, out: OutputStream) {
        try {
            val json = if (body.isNotBlank()) JSONObject(body) else JSONObject()
            val action = if (path.contains("action=")) {
                path.substringAfter("action=").substringBefore("&")
            } else {
                json.optString("action", "start")
            }

            when (action) {
                "start" -> {
                    val useFront = json.optBoolean("useFrontCamera", false)
                    streamManager.startStreaming(context, useFront)
                    sendHttpResponse(out, 200, "application/json", "{\"success\":true,\"streaming\":true}")
                }
                "stop" -> {
                    streamManager.stopStreaming()
                    sendHttpResponse(out, 200, "application/json", "{\"success\":true,\"streaming\":false}")
                }
                "switch" -> {
                    streamManager.switchCamera(context)
                    val isFront = streamManager.isFrontCamera.value
                    sendHttpResponse(out, 200, "application/json", "{\"success\":true,\"isFrontCamera\":$isFront}")
                }
                "torch" -> {
                    val enabled = json.optBoolean("enabled", true)
                    streamManager.toggleTorch(enabled)
                    sendHttpResponse(out, 200, "application/json", "{\"success\":true,\"torch\":$enabled}")
                }
                else -> {
                    sendHttpResponse(out, 400, "application/json", "{\"error\":\"Unknown camera action: $action\"}")
                }
            }
        } catch (e: Exception) {
            sendHttpResponse(out, 500, "application/json", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun handleGetCameraFrame(out: OutputStream) {
        // Ensure streamer is running
        if (!streamManager.isStreamingActive.value) {
            streamManager.startStreaming(context)
        }

        val frame = streamManager.getLatestFrame()
        if (frame != null && frame.isNotEmpty()) {
            val header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: image/jpeg\r\n" +
                    "Content-Length: ${frame.size}\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Cache-Control: no-cache\r\n\r\n"
            out.write(header.toByteArray(StandardCharsets.UTF_8))
            out.write(frame)
            out.flush()
        } else {
            sendHttpResponse(out, 503, "application/json", "{\"error\":\"Camera frame initializing\"}")
        }
    }

    private suspend fun handleCameraMjpegStream(socket: Socket, out: OutputStream) {
        if (!streamManager.isStreamingActive.value) {
            streamManager.startStreaming(context)
        }

        val header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: multipart/x-mixed-replace; boundary=--frame\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                "Connection: close\r\n\r\n"
        out.write(header.toByteArray(StandardCharsets.UTF_8))
        out.flush()

        var consecutiveNulls = 0
        while (_isRunning.value && streamManager.isStreamingActive.value && !socket.isClosed) {
            val frame = streamManager.getLatestFrame()
            if (frame != null && frame.isNotEmpty()) {
                consecutiveNulls = 0
                val partHeader = "--frame\r\n" +
                        "Content-Type: image/jpeg\r\n" +
                        "Content-Length: ${frame.size}\r\n\r\n"
                out.write(partHeader.toByteArray(StandardCharsets.UTF_8))
                out.write(frame)
                out.write("\r\n".toByteArray(StandardCharsets.UTF_8))
                out.flush()
            } else {
                consecutiveNulls++
                if (consecutiveNulls > 60) { // 6 seconds without frame
                    break
                }
            }
            delay(66) // ~15 FPS
        }
    }

    private fun sendHttpResponse(out: OutputStream, code: Int, contentType: String, body: String) {
        val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
        val statusText = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            503 -> "Service Unavailable"
            else -> "Error"
        }
        val response = "HTTP/1.1 $code $statusText\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${bodyBytes.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n"
        out.write(response.toByteArray(StandardCharsets.UTF_8))
        out.write(bodyBytes)
        out.flush()
    }

    fun stop() {
        _isRunning.value = false
        _connectedParentIp.value = null
        streamManager.stopStreaming()

        try {
            acceptJob?.cancel()
            udpJob?.cancel()
            serverSocket?.close()
            udpSocket?.close()
            SafeLogger.i(TAG, "Child P2P Server stopped")
        } catch (e: Exception) {
            SafeLogger.e(TAG, "Error stopping P2P server: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "ChildP2PServer"

        @Volatile
        private var instance: ChildP2PServer? = null

        fun getInstance(context: Context, appContainer: AppContainer): ChildP2PServer {
            return instance ?: synchronized(this) {
                instance ?: ChildP2PServer(context.applicationContext, appContainer).also { instance = it }
            }
        }
    }
}
