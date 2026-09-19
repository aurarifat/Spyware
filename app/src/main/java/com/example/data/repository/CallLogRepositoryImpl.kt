package com.example.data.repository

import android.annotation.SuppressLint
import android.content.Context
import android.provider.CallLog
import com.example.core.common.AppError
import com.example.core.common.AppResult
import com.example.core.common.SafeLogger
import com.example.core.permissions.PermissionManager
import com.example.domain.model.CallEntry
import com.example.domain.repository.ICallLogRepository

/**
 * Privacy-Preserving Call Log Repository.
 *
 * Google Play Sensitive Permission Policy & Data Minimization:
 * 1. READ_CALL_LOG is a high-risk restricted permission on Google Play.
 * 2. In this application, call log access is strictly opt-in and disabled by default until the user explicitly consents.
 * 3. We retrieve ONLY the last 10 entries (configurable ceiling).
 * 4. We mask the central digits of phone numbers (e.g. +1 234 *** 890) to minimize personal data exposure.
 * 5. We NEVER access SMS, MMS, voicemails, audio recordings, or message bodies.
 */
class CallLogRepositoryImpl(
    private val context: Context,
    private val permissionManager: PermissionManager
) : ICallLogRepository {

    @SuppressLint("MissingPermission")
    override suspend fun getRecentCalls(limit: Int): AppResult<List<CallEntry>> {
        if (!permissionManager.hasCallLogPermission()) {
            return AppResult.Error(AppError.CallLogAccessDenied)
        }

        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE
        )
        val sortOrder = "${CallLog.Calls.DATE} DESC"

        val callList = mutableListOf<CallEntry>()

        return try {
            val cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )

            cursor?.use {
                val idIdx = it.getColumnIndex(CallLog.Calls._ID)
                val nameIdx = it.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val numIdx = it.getColumnIndex(CallLog.Calls.NUMBER)
                val typeIdx = it.getColumnIndex(CallLog.Calls.TYPE)
                val dateIdx = it.getColumnIndex(CallLog.Calls.DATE)

                while (it.moveToNext() && callList.size < limit) {
                    val id = if (idIdx != -1) it.getString(idIdx) else ""
                    val rawName = if (nameIdx != -1) it.getString(nameIdx) else null
                    val rawNumber = if (numIdx != -1) it.getString(numIdx) else ""
                    val typeCode = if (typeIdx != -1) it.getInt(typeIdx) else CallLog.Calls.INCOMING_TYPE
                    val date = if (dateIdx != -1) it.getLong(dateIdx) else 0L

                    val typeStr = when (typeCode) {
                        CallLog.Calls.INCOMING_TYPE -> "INCOMING"
                        CallLog.Calls.OUTGOING_TYPE -> "OUTGOING"
                        CallLog.Calls.MISSED_TYPE -> "MISSED"
                        CallLog.Calls.REJECTED_TYPE -> "REJECTED"
                        else -> "CALL"
                    }

                    callList.add(
                        CallEntry(
                            callId = id.ifBlank { "call_${System.currentTimeMillis()}" },
                            name = if (!rawName.isNullOrBlank()) rawName else "Unknown Contact",
                            number = maskPhoneNumber(rawNumber),
                            type = typeStr,
                            timestamp = date
                        )
                    )
                }
            }

            AppResult.Success(callList)
        } catch (e: Exception) {
            SafeLogger.e(TAG, "Call log query failed: ${e.message}")
            AppResult.Error(AppError.UnknownError(e.message ?: "Failed to read call log"))
        }
    }

    /**
     * Masks the interior digits of a phone number to preserve privacy while allowing parental verification.
     */
    private fun maskPhoneNumber(raw: String): String {
        if (raw.isBlank()) return "Unknown"
        val clean = raw.trim()
        if (clean.length <= 4) return clean
        return if (clean.length <= 7) {
            "${clean.take(2)}***${clean.takeLast(2)}"
        } else {
            "${clean.take(4)}****${clean.takeLast(3)}"
        }
    }

    companion object {
        private const val TAG = "CallLogRepository"
    }
}
