package com.example.data.repository

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import com.example.core.common.AppError
import com.example.core.common.AppResult
import com.example.core.common.SafeLogger
import com.example.core.permissions.PermissionManager
import com.example.domain.repository.ILocationRepository
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Privacy-Preserving Location Repository.
 *
 * Foreground vs Background Location Architecture:
 * - We intentionally do NOT request ACCESS_BACKGROUND_LOCATION.
 * - Under Google Play's sensitive location policy, background location is restricted to apps where core functionality
 *   cannot function without continuous background tracking.
 * - Periodic foreground-service updates and explicit parent "Refresh" queries retrieve the location while the transparent
 *   Foreground Service is running with visible notification, complying with Android Foreground Service DATA_SYNC guidelines.
 * - Store only minimal coordinates and timestamp; no continuous trajectory logging or unnecessary precision tracking.
 */
class LocationRepositoryImpl(
    private val context: Context,
    private val permissionManager: PermissionManager
) : ILocationRepository {

    private val fusedLocationClient: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }

    @SuppressLint("MissingPermission")
    override suspend fun getCurrentLocation(): AppResult<Pair<Double, Double>?> {
        if (!permissionManager.hasLocationPermission()) {
            return AppResult.Error(AppError.PermissionDenied("ACCESS_FINE_LOCATION / ACCESS_COARSE_LOCATION"))
        }

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val isGpsEnabled = locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
        val isNetworkEnabled = locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true

        if (!isGpsEnabled && !isNetworkEnabled) {
            SafeLogger.w(TAG, "Location providers are disabled by user")
            // Try last known location as fallback
            val lastKnown = tryLastKnownLocation()
            return if (lastKnown != null) {
                AppResult.Success(lastKnown)
            } else {
                AppResult.Error(AppError.LocationUnavailable("Device GPS/Location services are turned off"))
            }
        }

        return try {
            val tokenSource = CancellationTokenSource()
            // 8-second timeout for location retrieval to avoid hanging coroutine
            val location = withTimeoutOrNull(8000L) {
                fusedLocationClient.getCurrentLocation(
                    Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                    tokenSource.token
                ).await()
            }

            if (location != null) {
                AppResult.Success(Pair(location.latitude, location.longitude))
            } else {
                val fallback = tryLastKnownLocation()
                if (fallback != null) {
                    AppResult.Success(fallback)
                } else {
                    AppResult.Success(null)
                }
            }
        } catch (e: Exception) {
            SafeLogger.e(TAG, "Failed to retrieve location: ${e.message}")
            val fallback = tryLastKnownLocation()
            if (fallback != null) {
                AppResult.Success(fallback)
            } else {
                AppResult.Error(AppError.LocationUnavailable(e.message ?: "Unknown location error"))
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun tryLastKnownLocation(): Pair<Double, Double>? {
        return try {
            val lastLoc = fusedLocationClient.lastLocation.await()
            if (lastLoc != null) Pair(lastLoc.latitude, lastLoc.longitude) else null
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "LocationRepository"
    }
}
