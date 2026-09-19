package com.example.core.permissions

/**
 * State representation for each permission item in the onboarding and settings UI.
 */
sealed interface PermissionState {
    data object Granted : PermissionState
    data object Denied : PermissionState
    data object PermanentlyDenied : PermissionState
    data object SpecialSettingsRequired : PermissionState
}

data class PermissionItemUiState(
    val type: PermissionType,
    val isGranted: Boolean,
    val state: PermissionState = if (isGranted) PermissionState.Granted else PermissionState.Denied
)
