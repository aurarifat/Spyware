package com.example.data.repository

import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import com.example.core.common.AppError
import com.example.core.common.AppResult
import com.example.core.common.SafeLogger
import com.example.core.permissions.PermissionManager
import com.example.domain.model.AppUsageEntry
import com.example.domain.repository.IUsageStatsRepository
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Digital Wellbeing Screen Time & Application Usage Repository.
 *
 * Special Access Disclosure:
 * - [android.Manifest.permission.PACKAGE_USAGE_STATS] is a privileged system AppOps permission.
 * - Android explicitly prevents requesting this permission via normal runtime permission dialogs.
 * - The user must manually navigate to system Settings (via Settings.ACTION_USAGE_ACCESS_SETTINGS)
 *   and flip the toggle for this application.
 */
class UsageStatsRepositoryImpl(
    private val context: Context,
    private val permissionManager: PermissionManager
) : IUsageStatsRepository {

    private val usageStatsManager: UsageStatsManager? by lazy {
        context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
    }

    private val packageManager: PackageManager by lazy {
        context.packageManager
    }

    override suspend fun getTodayAppUsage(): AppResult<List<AppUsageEntry>> {
        if (!permissionManager.hasUsageStatsPermission()) {
            return AppResult.Error(AppError.UsageAccessRequired)
        }

        val usm = usageStatsManager ?: return AppResult.Error(AppError.HardwareNotSupported("UsageStatsManager unavailable"))

        val calendar = Calendar.getInstance(TimeZone.getDefault()).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startTime = calendar.timeInMillis
        val endTime = System.currentTimeMillis()

        return try {
            val statsList: List<UsageStats> = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                startTime,
                endTime
            )

            if (statsList.isNullOrEmpty()) {
                SafeLogger.w(TAG, "Empty usage stats returned by system query")
                return AppResult.Success(emptyList())
            }

            // Aggregate foreground time per package (as queryUsageStats may contain slice entries)
            val aggregated = mutableMapOf<String, Long>()
            for (stat in statsList) {
                val duration = stat.totalTimeInForeground
                if (duration > 60_000L) { // Only keep apps used for at least 1 minute
                    aggregated[stat.packageName] = (aggregated[stat.packageName] ?: 0L) + duration
                }
            }

            // Filter out system launchers/system packages and sort descending
            val entries = aggregated.entries
                .filterNot { isIgnoredSystemPackage(it.key) }
                .sortedByDescending { it.value }
                .take(15) // Top 15 apps
                .map { (pkg, durationMs) ->
                    val appName = resolveAppLabel(pkg)
                    AppUsageEntry(
                        packageName = pkg,
                        appName = appName,
                        foregroundTimeMs = durationMs,
                        foregroundTimeFormatted = formatDuration(durationMs)
                    )
                }

            AppResult.Success(entries)
        } catch (e: Exception) {
            SafeLogger.e(TAG, "Failed to calculate usage stats: ${e.message}")
            AppResult.Error(AppError.UnknownError(e.message ?: "Failed to read usage stats"))
        }
    }

    override suspend fun getTodayTotalScreenTimeMs(): Long {
        if (!permissionManager.hasUsageStatsPermission()) return 0L
        val result = getTodayAppUsage()
        return if (result is AppResult.Success) {
            result.data.sumOf { it.foregroundTimeMs }
        } else {
            0L
        }
    }

    private fun resolveAppLabel(packageName: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName.substringAfterLast(".").replaceFirstChar { it.uppercase() }
        }
    }

    private fun isIgnoredSystemPackage(packageName: String): Boolean {
        return packageName.startsWith("com.android.systemui") ||
                packageName.startsWith("com.google.android.inputmethod") ||
                packageName == context.packageName
    }

    private fun formatDuration(durationMs: Long): String {
        val totalMinutes = durationMs / (1000 * 60)
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
            hours > 0 -> "${hours}h"
            else -> "${minutes}m"
        }
    }

    companion object {
        private const val TAG = "UsageStatsRepository"
    }
}
