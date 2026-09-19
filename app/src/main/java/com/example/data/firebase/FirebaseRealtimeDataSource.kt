package com.example.data.firebase

import android.content.Context
import com.example.core.common.AppError
import com.example.core.common.AppResult
import com.example.core.common.SafeLogger
import com.example.core.firebase.FirebasePaths
import com.example.domain.model.AppUsageEntry
import com.example.domain.model.CallEntry
import com.example.domain.model.ChildDeviceFullState
import com.example.domain.model.CommandExecutionResult
import com.example.domain.model.DeviceMetadata
import com.example.domain.model.DeviceStatus
import com.example.domain.model.EnrollmentConsent
import com.example.domain.model.LinkedChildDevice
import com.example.domain.model.MediaEntry
import com.example.domain.model.RemoteFlashlightCommand
import com.example.domain.model.RemoteRefreshCommand
import com.google.firebase.FirebaseApp
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Data Source for Firebase Realtime Database with robust fallback mechanism.
 * Uses structured concurrency, coroutine tasks await(), and clean Flow listeners.
 */
class FirebaseRealtimeDataSource(private val context: Context) {

    private val isFirebaseAvailable: Boolean by lazy {
        try {
            FirebaseApp.getApps(context).isNotEmpty()
        } catch (e: Exception) {
            SafeLogger.w(TAG, "FirebaseApp not initialized. Falling back to local memory synchronization.", e)
            false
        }
    }

    private val database: FirebaseDatabase? by lazy {
        if (isFirebaseAvailable) {
            try {
                FirebaseDatabase.getInstance().apply {
                    setPersistenceEnabled(true)
                }
            } catch (e: Exception) {
                SafeLogger.w(TAG, "FirebaseDatabase initialization warning: ${e.message}")
                try {
                    FirebaseDatabase.getInstance()
                } catch (e2: Exception) {
                    null
                }
            }
        } else {
            null
        }
    }

    // In-Memory fallback store for preview or offline sandbox testing
    private val localDeviceStates = MutableStateFlow<Map<String, ChildDeviceFullState>>(emptyMap())
    private val localLinkedDevices = MutableStateFlow<Map<String, List<LinkedChildDevice>>>(emptyMap())

    private fun getRef(path: String): DatabaseReference? {
        return database?.getReference(path)
    }

    suspend fun setValue(path: String, value: Any?): AppResult<Unit> {
        val ref = getRef(path)
        return if (ref != null) {
            try {
                ref.setValue(value).await()
                AppResult.Success(Unit)
            } catch (e: Exception) {
                SafeLogger.e(TAG, "Firebase write error at $path: ${e.message}")
                AppResult.Error(AppError.FirebaseUnavailable(e.message ?: "Failed to write to Firebase"))
            }
        } else {
            // Local fallback simulation
            SafeLogger.d(TAG, "Simulated write at $path: $value")
            AppResult.Success(Unit)
        }
    }

    suspend fun saveEnrollment(deviceId: String, consent: EnrollmentConsent): AppResult<Unit> {
        val map = mapOf(
            "enabled" to consent.enabled,
            "consentVersion" to consent.consentVersion,
            "consentTimestamp" to consent.consentTimestamp,
            "parentUid" to consent.parentUid,
            "parentDisplayName" to consent.parentDisplayName
        )
        val result = setValue(FirebasePaths.deviceEnrollmentPath(deviceId), map)
        if (result is AppResult.Success) {
            updateLocalState(deviceId) { it.copy(enrollment = consent, parentUid = consent.parentUid) }
        }
        return result
    }

    suspend fun syncStatus(deviceId: String, status: DeviceStatus): AppResult<Unit> {
        val usageList = status.appUsage.map {
            mapOf(
                "packageName" to it.packageName,
                "appName" to it.appName,
                "foregroundTimeMs" to it.foregroundTimeMs,
                "foregroundTimeFormatted" to it.foregroundTimeFormatted
            )
        }
        val map = mutableMapOf<String, Any?>(
            "lastSeen" to status.lastSeen,
            "batteryLevel" to status.batteryLevel,
            "isCharging" to status.isCharging,
            "wifiSsid" to (status.wifiSsid ?: "Not connected"),
            "totalScreenTimeMs" to status.totalScreenTimeMs,
            "totalScreenTimeFormatted" to status.totalScreenTimeFormatted,
            "appUsage" to usageList
        )
        if (status.latitude != null && status.longitude != null) {
            map["latitude"] = status.latitude
            map["longitude"] = status.longitude
            map["locationTimestamp"] = status.locationTimestamp ?: System.currentTimeMillis()
        }

        val result = setValue(FirebasePaths.deviceStatusPath(deviceId), map)
        if (result is AppResult.Success) {
            updateLocalState(deviceId) { it.copy(status = status) }
        }
        return result
    }

    suspend fun syncMetadata(deviceId: String, metadata: DeviceMetadata): AppResult<Unit> {
        val map = mapOf(
            "appVersion" to metadata.appVersion,
            "androidVersion" to metadata.androidVersion,
            "model" to metadata.model,
            "manufacturer" to metadata.manufacturer,
            "lastUpdated" to metadata.lastUpdated
        )
        val result = setValue(FirebasePaths.deviceMetadataPath(deviceId), map)
        if (result is AppResult.Success) {
            updateLocalState(deviceId) { it.copy(metadata = metadata) }
        }
        return result
    }

    suspend fun syncPermissions(deviceId: String, permissions: Map<String, Boolean>): AppResult<Unit> {
        val result = setValue(FirebasePaths.devicePermissionsPath(deviceId), permissions)
        if (result is AppResult.Success) {
            updateLocalState(deviceId) { it.copy(permissions = permissions) }
        }
        return result
    }

    suspend fun syncCalls(deviceId: String, calls: List<CallEntry>): AppResult<Unit> {
        val callsMap = calls.associate { call ->
            call.callId to mapOf(
                "name" to call.name,
                "number" to call.number,
                "type" to call.type,
                "timestamp" to call.timestamp
            )
        }
        val result = setValue(FirebasePaths.deviceCallsPath(deviceId), callsMap)
        if (result is AppResult.Success) {
            updateLocalState(deviceId) { it.copy(calls = calls) }
        }
        return result
    }

    suspend fun syncMedia(deviceId: String, media: List<MediaEntry>): AppResult<Unit> {
        val mediaMap = media.associate { item ->
            item.mediaId to mapOf(
                "displayName" to item.displayName,
                "relativePath" to item.relativePath,
                "contentUri" to item.contentUri,
                "thumbnailBase64" to item.thumbnailBase64,
                "dateAdded" to item.dateAdded
            )
        }
        val result = setValue(FirebasePaths.deviceMediaPath(deviceId), mediaMap)
        if (result is AppResult.Success) {
            updateLocalState(deviceId) { it.copy(media = media) }
        }
        return result
    }

    suspend fun linkChildToParent(parentUid: String, device: LinkedChildDevice): AppResult<Unit> {
        val map = mapOf(
            "childUid" to device.childUid,
            "deviceName" to device.deviceName,
            "enrolledAt" to device.enrolledAt,
            "status" to device.status
        )
        val path = FirebasePaths.parentLinkedDeviceItemPath(parentUid, device.deviceId)
        val result = setValue(path, map)
        if (result is AppResult.Success) {
            val currentList = localLinkedDevices.value[parentUid] ?: emptyList()
            val updated = currentList.filter { it.deviceId != device.deviceId } + device
            localLinkedDevices.value = localLinkedDevices.value + (parentUid to updated)
        }
        return result
    }

    suspend fun unlinkDevice(parentUid: String, deviceId: String): AppResult<Unit> {
        val ref = getRef(FirebasePaths.parentLinkedDeviceItemPath(parentUid, deviceId))
        if (ref != null) {
            try {
                ref.removeValue().await()
            } catch (e: Exception) {
                SafeLogger.e(TAG, "Unlink error: ${e.message}")
            }
        }
        val currentList = localLinkedDevices.value[parentUid] ?: emptyList()
        localLinkedDevices.value = localLinkedDevices.value + (parentUid to currentList.filter { it.deviceId != deviceId })
        return AppResult.Success(Unit)
    }

    suspend fun sendCommand(
        deviceId: String,
        commandName: String,
        payload: Map<String, Any?>
    ): AppResult<Unit> {
        val path = FirebasePaths.commandPath(deviceId, commandName)
        val result = setValue(path, payload)
        if (result is AppResult.Success) {
            if (commandName == FirebasePaths.CMD_FLASHLIGHT) {
                val enabled = payload["enabled"] as? Boolean ?: false
                val cmd = RemoteFlashlightCommand(
                    enabled = enabled,
                    requestedAt = (payload["requestedAt"] as? Long) ?: System.currentTimeMillis(),
                    requestedBy = (payload["requestedBy"] as? String) ?: ""
                )
                updateLocalState(deviceId) { it.copy(flashlightCommand = cmd) }
            } else if (commandName == FirebasePaths.CMD_REFRESH_STATUS) {
                val cmd = RemoteRefreshCommand(
                    requested = true,
                    requestedAt = (payload["requestedAt"] as? Long) ?: System.currentTimeMillis(),
                    requestedBy = (payload["requestedBy"] as? String) ?: ""
                )
                updateLocalState(deviceId) { it.copy(refreshCommand = cmd) }
            }
        }
        return result
    }

    suspend fun acknowledgeCommand(
        deviceId: String,
        commandName: String,
        result: CommandExecutionResult
    ): AppResult<Unit> {
        val path = FirebasePaths.commandResultPath(deviceId, commandName)
        val payload = mapOf(
            "commandName" to result.commandName,
            "success" to result.success,
            "executedAt" to result.executedAt,
            "error" to result.error
        )
        val res = setValue(path, payload)
        if (res is AppResult.Success) {
            updateLocalState(deviceId) {
                if (commandName == FirebasePaths.CMD_FLASHLIGHT) {
                    it.copy(lastFlashlightResult = result)
                } else {
                    it.copy(lastRefreshResult = result)
                }
            }
        }
        return res
    }

    fun observeParentLinkedDevices(parentUid: String): Flow<List<LinkedChildDevice>> {
        val ref = getRef(FirebasePaths.parentLinkedDevicesPath(parentUid))
        if (ref == null) {
            return callbackFlow {
                val job = launch {
                    localLinkedDevices.collect { map ->
                        trySend(map[parentUid] ?: emptyList())
                    }
                }
                awaitClose { job.cancel() }
            }
        }

        return callbackFlow {
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val list = mutableListOf<LinkedChildDevice>()
                    for (childSnap in snapshot.children) {
                        val deviceId = childSnap.key ?: continue
                        val childUid = childSnap.child("childUid").getValue(String::class.java) ?: ""
                        val deviceName = childSnap.child("deviceName").getValue(String::class.java) ?: "Child Device"
                        val enrolledAt = childSnap.child("enrolledAt").getValue(Long::class.java) ?: 0L
                        val status = childSnap.child("status").getValue(String::class.java) ?: "ACTIVE"
                        list.add(LinkedChildDevice(deviceId, childUid, deviceName, enrolledAt, status))
                    }
                    trySend(list)
                }

                override fun onCancelled(error: DatabaseError) {
                    SafeLogger.w(TAG, "Parent linked devices listener cancelled: ${error.message}")
                }
            }
            ref.addValueEventListener(listener)
            awaitClose { ref.removeEventListener(listener) }
        }
    }

    fun observeDeviceFullState(deviceId: String): Flow<ChildDeviceFullState?> {
        val ref = getRef(FirebasePaths.deviceRootPath(deviceId))
        if (ref == null) {
            return callbackFlow {
                val job = launch {
                    localDeviceStates.collect { map ->
                        trySend(map[deviceId])
                    }
                }
                awaitClose { job.cancel() }
            }
        }

        return callbackFlow {
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!snapshot.exists()) {
                        trySend(null)
                        return
                    }

                    // Parse Enrollment
                    val enrollSnap = snapshot.child(FirebasePaths.ENROLLMENT)
                    val enrollment = EnrollmentConsent(
                        enabled = enrollSnap.child("enabled").getValue(Boolean::class.java) ?: false,
                        consentVersion = enrollSnap.child("consentVersion").getValue(String::class.java) ?: "1.0",
                        consentTimestamp = enrollSnap.child("consentTimestamp").getValue(Long::class.java) ?: 0L,
                        parentUid = enrollSnap.child("parentUid").getValue(String::class.java) ?: "",
                        parentDisplayName = enrollSnap.child("parentDisplayName").getValue(String::class.java) ?: ""
                    )

                    // Parse Status
                    val statusSnap = snapshot.child(FirebasePaths.STATUS)
                    val appUsageList = mutableListOf<AppUsageEntry>()
                    for (usageSnap in statusSnap.child("appUsage").children) {
                        appUsageList.add(
                            AppUsageEntry(
                                packageName = usageSnap.child("packageName").getValue(String::class.java) ?: "",
                                appName = usageSnap.child("appName").getValue(String::class.java) ?: "",
                                foregroundTimeMs = usageSnap.child("foregroundTimeMs").getValue(Long::class.java) ?: 0L,
                                foregroundTimeFormatted = usageSnap.child("foregroundTimeFormatted").getValue(String::class.java) ?: ""
                            )
                        )
                    }
                    val status = DeviceStatus(
                        lastSeen = statusSnap.child("lastSeen").getValue(Long::class.java) ?: 0L,
                        batteryLevel = statusSnap.child("batteryLevel").getValue(Int::class.java) ?: 0,
                        isCharging = statusSnap.child("isCharging").getValue(Boolean::class.java) ?: false,
                        latitude = statusSnap.child("latitude").getValue(Double::class.java),
                        longitude = statusSnap.child("longitude").getValue(Double::class.java),
                        locationTimestamp = statusSnap.child("locationTimestamp").getValue(Long::class.java),
                        wifiSsid = statusSnap.child("wifiSsid").getValue(String::class.java),
                        totalScreenTimeMs = statusSnap.child("totalScreenTimeMs").getValue(Long::class.java) ?: 0L,
                        totalScreenTimeFormatted = statusSnap.child("totalScreenTimeFormatted").getValue(String::class.java) ?: "0m",
                        appUsage = appUsageList
                    )

                    // Parse Calls
                    val callsList = mutableListOf<CallEntry>()
                    for (callSnap in snapshot.child(FirebasePaths.CALLS).children) {
                        callsList.add(
                            CallEntry(
                                callId = callSnap.key ?: "",
                                name = callSnap.child("name").getValue(String::class.java) ?: "Unknown",
                                number = callSnap.child("number").getValue(String::class.java) ?: "",
                                type = callSnap.child("type").getValue(String::class.java) ?: "INCOMING",
                                timestamp = callSnap.child("timestamp").getValue(Long::class.java) ?: 0L
                            )
                        )
                    }

                    // Parse Media
                    val mediaList = mutableListOf<MediaEntry>()
                    for (mediaSnap in snapshot.child(FirebasePaths.MEDIA).children) {
                        mediaList.add(
                            MediaEntry(
                                mediaId = mediaSnap.key ?: "",
                                displayName = mediaSnap.child("displayName").getValue(String::class.java) ?: "",
                                relativePath = mediaSnap.child("relativePath").getValue(String::class.java) ?: "",
                                contentUri = mediaSnap.child("contentUri").getValue(String::class.java) ?: "",
                                thumbnailBase64 = mediaSnap.child("thumbnailBase64").getValue(String::class.java) ?: "",
                                dateAdded = mediaSnap.child("dateAdded").getValue(Long::class.java) ?: 0L
                            )
                        )
                    }

                    // Parse Commands
                    val flashCmdSnap = snapshot.child(FirebasePaths.COMMANDS).child(FirebasePaths.CMD_FLASHLIGHT)
                    val flashCmd = if (flashCmdSnap.exists()) {
                        RemoteFlashlightCommand(
                            enabled = flashCmdSnap.child("enabled").getValue(Boolean::class.java) ?: false,
                            requestedAt = flashCmdSnap.child("requestedAt").getValue(Long::class.java) ?: 0L,
                            requestedBy = flashCmdSnap.child("requestedBy").getValue(String::class.java) ?: ""
                        )
                    } else null

                    val refCmdSnap = snapshot.child(FirebasePaths.COMMANDS).child(FirebasePaths.CMD_REFRESH_STATUS)
                    val refCmd = if (refCmdSnap.exists()) {
                        RemoteRefreshCommand(
                            requested = refCmdSnap.child("requested").getValue(Boolean::class.java) ?: false,
                            requestedAt = refCmdSnap.child("requestedAt").getValue(Long::class.java) ?: 0L,
                            requestedBy = refCmdSnap.child("requestedBy").getValue(String::class.java) ?: ""
                        )
                    } else null

                    // Parse Command Results
                    val flashResSnap = snapshot.child(FirebasePaths.COMMAND_RESULTS).child(FirebasePaths.CMD_FLASHLIGHT)
                    val flashRes = if (flashResSnap.exists()) {
                        CommandExecutionResult(
                            commandName = FirebasePaths.CMD_FLASHLIGHT,
                            success = flashResSnap.child("success").getValue(Boolean::class.java) ?: false,
                            executedAt = flashResSnap.child("executedAt").getValue(Long::class.java) ?: 0L,
                            error = flashResSnap.child("error").getValue(String::class.java)
                        )
                    } else null

                    val refResSnap = snapshot.child(FirebasePaths.COMMAND_RESULTS).child(FirebasePaths.CMD_REFRESH_STATUS)
                    val refRes = if (refResSnap.exists()) {
                        CommandExecutionResult(
                            commandName = FirebasePaths.CMD_REFRESH_STATUS,
                            success = refResSnap.child("success").getValue(Boolean::class.java) ?: false,
                            executedAt = refResSnap.child("executedAt").getValue(Long::class.java) ?: 0L,
                            error = refResSnap.child("error").getValue(String::class.java)
                        )
                    } else null

                    val fullState = ChildDeviceFullState(
                        deviceId = deviceId,
                        ownerUid = snapshot.child("ownerUid").getValue(String::class.java) ?: "",
                        parentUid = enrollment.parentUid,
                        enrollment = enrollment,
                        status = status,
                        calls = callsList,
                        media = mediaList,
                        flashlightCommand = flashCmd,
                        refreshCommand = refCmd,
                        lastFlashlightResult = flashRes,
                        lastRefreshResult = refRes
                    )
                    trySend(fullState)
                }

                override fun onCancelled(error: DatabaseError) {
                    SafeLogger.w(TAG, "Device snapshot listener cancelled: ${error.message}")
                }
            }

            ref.addValueEventListener(listener)
            awaitClose { ref.removeEventListener(listener) }
        }
    }

    fun updateLocalState(deviceId: String, update: (ChildDeviceFullState) -> ChildDeviceFullState) {
        val current = localDeviceStates.value[deviceId] ?: ChildDeviceFullState(deviceId = deviceId)
        val updated = update(current)
        localDeviceStates.value = localDeviceStates.value + (deviceId to updated)
    }

    fun setLocalDeviceFullState(state: ChildDeviceFullState) {
        localDeviceStates.value = localDeviceStates.value + (state.deviceId to state)
    }

    companion object {
        private const val TAG = "FirebaseDataSource"
    }
}
