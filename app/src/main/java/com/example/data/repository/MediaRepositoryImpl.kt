package com.example.data.repository

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import com.example.core.common.AppError
import com.example.core.common.AppResult
import com.example.core.common.SafeLogger
import com.example.core.permissions.PermissionManager
import com.example.domain.model.MediaEntry
import com.example.domain.repository.IMediaRepository
import java.io.ByteArrayOutputStream

/**
 * Privacy-Preserving Media Metadata Repository.
 *
 * Scoped Storage & Image Preview Support:
 * 1. Complies with Android 10+ Scoped Storage guidelines.
 * 2. Android 13+ (API 33) uses granular [android.Manifest.permission.READ_MEDIA_IMAGES].
 * 3. Provides contentUri for high-resolution local viewing and lightweight compressed thumbnail for parent view.
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

                    val idLong = id.toLongOrNull() ?: 0L
                    val contentUri = if (idLong > 0L) {
                        ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, idLong).toString()
                    } else ""

                    val thumbnailBase64 = generateThumbnailBase64(idLong)

                    mediaList.add(
                        MediaEntry(
                            mediaId = id.ifBlank { "media_${System.currentTimeMillis()}" },
                            displayName = sanitizeFileName(name),
                            relativePath = path,
                            contentUri = contentUri,
                            thumbnailBase64 = thumbnailBase64,
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

    private fun generateThumbnailBase64(mediaId: Long): String {
        if (mediaId <= 0L) return ""
        return try {
            val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaId)
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(uri, android.util.Size(200, 200), null)
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Thumbnails.getThumbnail(
                    context.contentResolver,
                    mediaId,
                    MediaStore.Images.Thumbnails.MINI_KIND,
                    null
                )
            }
            if (bitmap != null) {
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 75, out)
                Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            } else ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun sanitizeFileName(name: String): String {
        return name.take(60)
    }

    companion object {
        private const val TAG = "MediaRepository"
    }
}
