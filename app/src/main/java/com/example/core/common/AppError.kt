package com.example.core.common

/**
 * Standard domain error model for the Family Digital Wellbeing application.
 * All operations return safe results typed with [AppError] rather than throwing unhandled exceptions.
 */
sealed interface AppError {
    data class PermissionDenied(val permission: String, val message: String = "Permission required: $permission") : AppError
    data class LocationUnavailable(val message: String = "Location hardware or network provider unavailable") : AppError
    data class FirebaseUnavailable(val message: String = "Firebase service unavailable or network connection failed") : AppError
    data object AuthenticationRequired : AppError
    data class CommandUnauthorized(val reason: String = "Command not authorized for this caller") : AppError
    data class CameraUnavailable(val reason: String = "Camera / flashlight hardware inaccessible") : AppError
    data object UsageAccessRequired : AppError
    data object MediaAccessDenied : AppError
    data object CallLogAccessDenied : AppError
    data object NetworkUnavailable : AppError
    data class HardwareNotSupported(val feature: String) : AppError
    data object DeviceUnlinked : AppError
    data class UnknownError(val message: String) : AppError
}
