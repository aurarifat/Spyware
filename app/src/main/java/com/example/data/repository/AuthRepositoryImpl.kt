package com.example.data.repository

import android.content.Context
import com.example.core.common.AppError
import com.example.core.common.AppResult
import com.example.core.common.SafeLogger
import com.example.domain.model.UserProfile
import com.example.domain.model.UserRole
import com.example.domain.repository.IAuthRepository
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await

class AuthRepositoryImpl(
    private val context: Context
) : IAuthRepository {

    private val auth: FirebaseAuth? by lazy {
        try {
            if (FirebaseApp.getApps(context).isNotEmpty()) {
                FirebaseAuth.getInstance()
            } else {
                null
            }
        } catch (e: Exception) {
            SafeLogger.w(TAG, "FirebaseAuth initialization fallback: ${e.message}")
            null
        }
    }

    private var localUser: UserProfile? = null

    override fun getCurrentUid(): String? {
        return auth?.currentUser?.uid ?: localUser?.uid
    }

    override fun getCurrentUser(): UserProfile? {
        val fbUser = auth?.currentUser
        if (fbUser != null) {
            return UserProfile(
                uid = fbUser.uid,
                role = UserRole.PARENT,
                displayName = fbUser.displayName ?: fbUser.email ?: "Family Parent"
            )
        }
        return localUser
    }

    override suspend fun signInAnonymously(): AppResult<UserProfile> {
        val fbAuth = auth
        return if (fbAuth != null) {
            try {
                val res = fbAuth.signInAnonymously().await()
                val uid = res.user?.uid ?: "child_${System.currentTimeMillis()}"
                val profile = UserProfile(uid = uid, role = UserRole.CHILD, displayName = "Child Device")
                localUser = profile
                AppResult.Success(profile)
            } catch (e: Exception) {
                SafeLogger.w(TAG, "Anonymous sign-in failed, using safe offline identity: ${e.message}")
                val fallbackProfile = UserProfile(
                    uid = "local_child_${System.currentTimeMillis().toString().takeLast(6)}",
                    role = UserRole.CHILD,
                    displayName = "Child Device"
                )
                localUser = fallbackProfile
                AppResult.Success(fallbackProfile)
            }
        } else {
            val fallbackProfile = UserProfile(
                uid = "local_child_${System.currentTimeMillis().toString().takeLast(6)}",
                role = UserRole.CHILD,
                displayName = "Child Device"
            )
            localUser = fallbackProfile
            AppResult.Success(fallbackProfile)
        }
    }

    override suspend fun signInWithEmail(email: String, pass: String): AppResult<UserProfile> {
        val fbAuth = auth
        return if (fbAuth != null) {
            try {
                val res = fbAuth.signInWithEmailAndPassword(email, pass).await()
                val uid = res.user?.uid ?: "parent_${System.currentTimeMillis()}"
                val profile = UserProfile(
                    uid = uid,
                    role = UserRole.PARENT,
                    displayName = email.substringBefore("@")
                )
                localUser = profile
                AppResult.Success(profile)
            } catch (e: Exception) {
                AppResult.Error(AppError.FirebaseUnavailable(e.message ?: "Email sign-in failed"))
            }
        } else {
            // Offline sandbox parent session
            val mockParent = UserProfile(
                uid = "parent_${email.hashCode().toString().takeLast(6)}",
                role = UserRole.PARENT,
                displayName = email.substringBefore("@")
            )
            localUser = mockParent
            AppResult.Success(mockParent)
        }
    }

    override suspend fun registerWithEmail(
        email: String,
        pass: String,
        role: UserRole,
        displayName: String
    ): AppResult<UserProfile> {
        val fbAuth = auth
        return if (fbAuth != null) {
            try {
                val res = fbAuth.createUserWithEmailAndPassword(email, pass).await()
                val uid = res.user?.uid ?: "usr_${System.currentTimeMillis()}"
                val profile = UserProfile(uid = uid, role = role, displayName = displayName)
                localUser = profile
                AppResult.Success(profile)
            } catch (e: Exception) {
                AppResult.Error(AppError.FirebaseUnavailable(e.message ?: "Registration failed"))
            }
        } else {
            val mockUser = UserProfile(
                uid = "usr_${email.hashCode().toString().takeLast(6)}",
                role = role,
                displayName = displayName.ifBlank { email.substringBefore("@") }
            )
            localUser = mockUser
            AppResult.Success(mockUser)
        }
    }

    override suspend fun signOut() {
        auth?.signOut()
        localUser = null
    }

    companion object {
        private const val TAG = "AuthRepository"
    }
}
