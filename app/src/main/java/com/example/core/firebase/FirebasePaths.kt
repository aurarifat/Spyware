package com.example.core.firebase

/**
 * Centralized Firebase Realtime Database schema paths.
 * Prevents hardcoded path strings across repositories and rules.
 */
object FirebasePaths {
    const val USERS = "users"
    const val PARENTS = "parents"
    const val DEVICES = "devices"

    // Subpaths under /users/{uid}
    const val FIELD_ROLE = "role"
    const val FIELD_DISPLAY_NAME = "displayName"
    const val FIELD_CREATED_AT = "createdAt"

    // Subpaths under /parents/{parentUid}
    const val LINKED_DEVICES = "linkedDevices"

    // Subpaths under /devices/{deviceId}
    const val ENROLLMENT = "enrollment"
    const val STATUS = "status"
    const val CALLS = "calls"
    const val MEDIA = "media"
    const val PERMISSIONS = "permissions"
    const val COMMANDS = "commands"
    const val COMMAND_RESULTS = "commandResults"
    const val METADATA = "metadata"

    // Helper functions for full paths
    fun userPath(uid: String) = "$USERS/$uid"
    fun parentLinkedDevicesPath(parentUid: String) = "$PARENTS/$parentUid/$LINKED_DEVICES"
    fun parentLinkedDeviceItemPath(parentUid: String, deviceId: String) = "$PARENTS/$parentUid/$LINKED_DEVICES/$deviceId"

    fun deviceRootPath(deviceId: String) = "$DEVICES/$deviceId"
    fun deviceEnrollmentPath(deviceId: String) = "$DEVICES/$deviceId/$ENROLLMENT"
    fun deviceStatusPath(deviceId: String) = "$DEVICES/$deviceId/$STATUS"
    fun deviceCallsPath(deviceId: String) = "$DEVICES/$deviceId/$CALLS"
    fun deviceMediaPath(deviceId: String) = "$DEVICES/$deviceId/$MEDIA"
    fun devicePermissionsPath(deviceId: String) = "$DEVICES/$deviceId/$PERMISSIONS"
    fun deviceCommandsPath(deviceId: String) = "$DEVICES/$deviceId/$COMMANDS"
    fun deviceCommandResultsPath(deviceId: String) = "$DEVICES/$deviceId/$COMMAND_RESULTS"
    fun deviceMetadataPath(deviceId: String) = "$DEVICES/$deviceId/$METADATA"

    // Specific commands
    const val CMD_FLASHLIGHT = "flashlight"
    const val CMD_REFRESH_STATUS = "refreshStatus"

    fun commandPath(deviceId: String, commandName: String) = "$DEVICES/$deviceId/$COMMANDS/$commandName"
    fun commandResultPath(deviceId: String, commandName: String) = "$DEVICES/$deviceId/$COMMAND_RESULTS/$commandName"
}
