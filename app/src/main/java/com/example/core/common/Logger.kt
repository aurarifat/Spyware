package com.example.core.common

import android.util.Log

/**
 * Mobile Security & Privacy Compliant Logger.
 * Strict rules:
 * 1. Never log raw phone numbers, contact names, or message contents.
 * 2. Never log raw GPS coordinates in release mode.
 * 3. Never log Firebase auth tokens or secrets.
 * 4. Mask device identifiers for privacy.
 */
object SafeLogger {
    private const val DEFAULT_TAG = "FamilyWellbeing"
    private var isDebug = true // Can be connected to BuildConfig.DEBUG

    fun setDebug(debug: Boolean) {
        isDebug = debug
    }

    fun d(tag: String = DEFAULT_TAG, message: String) {
        if (isDebug) {
            Log.d(tag, sanitize(message))
        }
    }

    fun i(tag: String = DEFAULT_TAG, message: String) {
        Log.i(tag, sanitize(message))
    }

    fun w(tag: String = DEFAULT_TAG, message: String, throwable: Throwable? = null) {
        Log.w(tag, sanitize(message), throwable)
    }

    fun e(tag: String = DEFAULT_TAG, message: String, throwable: Throwable? = null) {
        Log.e(tag, sanitize(message), throwable)
    }

    /**
     * Sanitizes strings to prevent inadvertent leakage of phone numbers or sensitive keys.
     */
    fun sanitize(input: String): String {
        // Redact 7-15 digit sequences that could be phone numbers
        var sanitized = input.replace(Regex("\\b\\+?[0-9]{7,15}\\b"), "[REDACTED_PHONE]")
        // Redact bearer tokens or auth tokens
        sanitized = sanitized.replace(Regex("(?i)token=([a-zA-Z0-9_-]+)"), "token=[REDACTED]")
        return sanitized
    }

    /**
     * Masks an identifier showing only the first 4 and last 4 characters.
     */
    fun maskId(id: String): String {
        if (id.length <= 8) return "****"
        return "${id.take(4)}...${id.takeLast(4)}"
    }
}
