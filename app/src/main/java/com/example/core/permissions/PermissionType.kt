package com.example.core.permissions

import android.Manifest
import android.os.Build

/**
 * Enumeration of all permissions utilized in Family Wellbeing with user-facing transparent disclosures.
 */
enum class PermissionType(
    val title: String,
    val description: String,
    val whyNeeded: String,
    val isSpecialAccess: Boolean = false
) {
    LOCATION(
        title = "Location Sharing",
        description = "Shares coarse or fine location coordinates when child status synchronizes.",
        whyNeeded = "Allows parents to verify the child is in a safe location such as school or home. Location is only synchronized periodically or upon explicit parent refresh request."
    ),
    USAGE_STATS(
        title = "Digital Wellbeing & Screen Time",
        description = "Analyzes app usage statistics and daily foreground screen time.",
        whyNeeded = "Enables parents and children to see daily screen time breakdown (e.g. YouTube, Games) to encourage healthy digital habits. Granted via Android Usage Access Settings.",
        isSpecialAccess = true
    ),
    CALL_LOG(
        title = "Recent Call Log (Opt-in)",
        description = "Provides read-only access to the last 10 incoming and outgoing calls.",
        whyNeeded = "Helps identify unusual or unknown calls. Private messages and audio are never accessed. Phone numbers are minimized and masked."
    ),
    MEDIA_METADATA(
        title = "Recent Photos Metadata (Opt-in)",
        description = "Reads recent photo file names and timestamps from the media gallery.",
        whyNeeded = "Enables awareness of recent media activity. Crucially, actual image files or photos are NEVER uploaded or copied to the cloud — only minimal metadata (title, date) is saved."
    ),
    CAMERA_FLASH(
        title = "Camera Flashlight Control",
        description = "Provides hardware access to activate the device flashlight.",
        whyNeeded = "Allows parents to trigger the device flashlight remotely if the phone is lost in the dark or as a safety signal. No camera photos or videos are ever taken or transmitted."
    ),
    NOTIFICATIONS(
        title = "Supervision Notification",
        description = "Required to display the mandatory persistent foreground service notification.",
        whyNeeded = "Ensures full transparency so the child always knows supervision and device synchronization are active, in strict accordance with Android platform rules."
    );

    /**
     * Resolves the manifest permission string(s) for standard runtime permissions based on device OS level.
     */
    fun getManifestPermissions(): List<String> {
        return when (this) {
            LOCATION -> listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            CALL_LOG -> listOf(Manifest.permission.READ_CALL_LOG)
            MEDIA_METADATA -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    listOf(Manifest.permission.READ_MEDIA_IMAGES)
                } else {
                    listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }
            CAMERA_FLASH -> listOf(Manifest.permission.CAMERA)
            NOTIFICATIONS -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    listOf(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    emptyList()
                }
            }
            USAGE_STATS -> emptyList() // Handled via Settings.ACTION_USAGE_ACCESS_SETTINGS
        }
    }
}
