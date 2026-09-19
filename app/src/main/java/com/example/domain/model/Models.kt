package com.example.domain.model

enum class UserRole {
    CHILD,
    PARENT
}

data class UserProfile(
    val uid: String = "",
    val role: UserRole = UserRole.PARENT,
    val displayName: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

data class EnrollmentConsent(
    val enabled: Boolean = false,
    val consentVersion: String = "1.0",
    val consentTimestamp: Long = 0L,
    val parentUid: String = "",
    val parentDisplayName: String = ""
)

data class AppUsageEntry(
    val packageName: String = "",
    val appName: String = "",
    val foregroundTimeMs: Long = 0L,
    val foregroundTimeFormatted: String = ""
)

data class DeviceStatus(
    val lastSeen: Long = 0L,
    val batteryLevel: Int = 0,
    val isCharging: Boolean = false,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationTimestamp: Long? = null,
    val wifiSsid: String? = null,
    val totalScreenTimeMs: Long = 0L,
    val totalScreenTimeFormatted: String = "0m",
    val appUsage: List<AppUsageEntry> = emptyList()
)

data class CallEntry(
    val callId: String = "",
    val name: String = "Unknown",
    val number: String = "",
    val type: String = "INCOMING",
    val timestamp: Long = 0L
)

data class MediaEntry(
    val mediaId: String = "",
    val displayName: String = "",
    val relativePath: String = "",
    val contentUri: String = "",
    val thumbnailBase64: String = "",
    val dateAdded: Long = 0L
)

data class RemoteFlashlightCommand(
    val enabled: Boolean = false,
    val requestedAt: Long = 0L,
    val requestedBy: String = ""
)

data class RemoteRefreshCommand(
    val requested: Boolean = false,
    val requestedAt: Long = 0L,
    val requestedBy: String = ""
)

data class CommandExecutionResult(
    val commandName: String = "",
    val success: Boolean = false,
    val executedAt: Long = 0L,
    val error: String? = null
)

data class DeviceMetadata(
    val appVersion: String = "1.0",
    val androidVersion: String = "",
    val model: String = "",
    val manufacturer: String = "",
    val lastUpdated: Long = System.currentTimeMillis()
)

data class LinkedChildDevice(
    val deviceId: String = "",
    val childUid: String = "",
    val deviceName: String = "",
    val enrolledAt: Long = 0L,
    val status: String = "ACTIVE"
)

data class ChildDeviceFullState(
    val deviceId: String = "",
    val ownerUid: String = "",
    val parentUid: String = "",
    val enrollment: EnrollmentConsent = EnrollmentConsent(),
    val status: DeviceStatus = DeviceStatus(),
    val calls: List<CallEntry> = emptyList(),
    val media: List<MediaEntry> = emptyList(),
    val permissions: Map<String, Boolean> = emptyMap(),
    val flashlightCommand: RemoteFlashlightCommand? = null,
    val refreshCommand: RemoteRefreshCommand? = null,
    val lastFlashlightResult: CommandExecutionResult? = null,
    val lastRefreshResult: CommandExecutionResult? = null,
    val metadata: DeviceMetadata = DeviceMetadata()
)
