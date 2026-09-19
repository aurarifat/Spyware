package com.example.data.repository

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.example.core.common.AppError
import com.example.core.common.AppResult
import com.example.core.common.SafeLogger
import com.example.core.permissions.PermissionManager
import com.example.domain.model.MediaEntry
import com.example.domain.repository.IMediaRepository

/**
 * Privacy-Preserving Media Metadata Repository.
 *
 * Scoped Storage & Zero-Upload Privacy Policy:
 * 1. Complies with Android 10+ Scoped Storage guidelines.
 * 2. Android 13+ (API 33) uses granular [android.Manifest.permission.READ_MEDIA_IMAGES].
 * 3. Crucially, NO raw image bytes or bitmap files are transmitted to the cloud or Firebase.
 * 4. Only minimal, safe metadata (display name, relative path if available, and date added) is synchronized.
 */
class MediaRepositoryImpl(
    private val context: Context,
    private val permissionManager: PermissionManager
) : IMediaRepository {

    override suspend fun getRecentMedia(limit: Int): AppResult<List<MediaEntry>> {
        if (!permissionManager.hasMediaPermission()) {
            return AppResult.Error(AppError.MediaAccessDenied)
        }

        val projection = mutableListOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection.add(MediaStore.Images.Media.RELATIVE_PATH)
        }

        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        val mediaList = mutableListOf<MediaEntry>()

        return try {
            val cursor = context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection.toTypedArray(),
                null,
                null,
                sortOrder
            )

            cursor?.use {
                val idIdx = it.getColumnIndex(MediaStore.Images.Media._ID)
                val nameIdx = it.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                val dateIdx = it.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                val pathIdx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    it.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                } else -1

                while (it.moveToNext() && mediaList.size < limit) {
                    val id = if (idIdx != -1) it.getString(idIdx) else ""
                    val name = if (nameIdx != -1) it.getString(nameIdx) else "Photo"
                    val dateAddedSec = if (dateIdx != -1) it.getLong(dateIdx) else 0L
                    val path = if (pathIdx != -1) it.getString(pathIdx) ?: "Pictures/" else "Pictures/"

                    mediaList.add(
                        MediaEntry(
                            mediaId = id.ifBlank { "media_${System.currentTimeMillis()}" },
                            displayName = sanitizeFileName(name),
                            relativePath = path,
                            dateAdded = dateAddedSec * 1000L // Convert to ms
                        )
                    )
                }
            }

            AppResult.Success(mediaList)
        } catch (e: Exception) {
            SafeLogger.e(TAG, "Media query failed: ${e.message}")
            AppResult.Error(AppError.UnknownError(e.message ?: "Failed to read media metadata"))
        }
    }

    private fun sanitizeFileName(name: String): String {
        return name.take(60)
    }

    companion object {
        private const val TAG = "MediaRepository"
    }
}
