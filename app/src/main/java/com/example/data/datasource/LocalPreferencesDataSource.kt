package com.example.data.datasource

import android.content.Context
import android.content.SharedPreferences
import com.example.domain.model.UserRole

class LocalPreferencesDataSource(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("family_wellbeing_local_prefs", Context.MODE_PRIVATE)

    fun getUserRole(): UserRole? {
        val roleStr = prefs.getString(KEY_ROLE, null) ?: return null
        return try {
            UserRole.valueOf(roleStr)
        } catch (e: Exception) {
            null
        }
    }

    fun setUserRole(role: UserRole) {
        prefs.edit().putString(KEY_ROLE, role.name).apply()
    }

    fun clearUserRole() {
        prefs.edit().remove(KEY_ROLE).apply()
    }

    fun getLinkedParentUid(): String? = prefs.getString(KEY_PARENT_UID, null)

    fun setLinkedParentUid(parentUid: String?) {
        prefs.edit().putString(KEY_PARENT_UID, parentUid).apply()
    }

    fun getChildDeviceName(): String = prefs.getString(KEY_CHILD_NAME, "Child's Phone") ?: "Child's Phone"

    fun setChildDeviceName(name: String) {
        prefs.edit().putString(KEY_CHILD_NAME, name).apply()
    }

    fun isConsentGiven(): Boolean = prefs.getBoolean(KEY_CONSENT_GIVEN, false)

    fun setConsentGiven(given: Boolean) {
        prefs.edit().putBoolean(KEY_CONSENT_GIVEN, given).apply()
    }

    companion object {
        private const val KEY_ROLE = "user_role"
        private const val KEY_PARENT_UID = "linked_parent_uid"
        private const val KEY_CHILD_NAME = "child_device_name"
        private const val KEY_CONSENT_GIVEN = "is_consent_given"
    }
}
