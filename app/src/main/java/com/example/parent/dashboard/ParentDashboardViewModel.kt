package com.example.parent.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.core.common.AppResult
import com.example.core.di.AppContainer
import com.example.domain.model.LinkedChildDevice
import com.example.domain.model.UserProfile
import com.example.domain.model.UserRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ParentDashboardUiState(
    val currentUser: UserProfile? = null,
    val isAuthenticated: Boolean = false,
    val isLoading: Boolean = false,
    val linkedDevices: List<LinkedChildDevice> = emptyList(),
    val errorMessage: String? = null,
    val successMessage: String? = null
)

class ParentDashboardViewModel(
    private val appContainer: AppContainer
) : ViewModel() {

    private val _uiState = MutableStateFlow(ParentDashboardUiState())
    val uiState: StateFlow<ParentDashboardUiState> = _uiState.asStateFlow()

    init {
        checkCurrentAuth()
    }

    private fun checkCurrentAuth() {
        val user = appContainer.authRepository.getCurrentUser()
        if (user != null) {
            _uiState.update { it.copy(currentUser = user, isAuthenticated = true) }
            observeDevices(user.uid)
        }
    }

    fun login(email: String, pass: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = appContainer.authRepository.signInWithEmail(email, pass)
            if (res is AppResult.Success) {
                _uiState.update {
                    it.copy(
                        currentUser = res.data,
                        isAuthenticated = true,
                        isLoading = false,
                        successMessage = "Welcome back, ${res.data.displayName}"
                    )
                }
                observeDevices(res.data.uid)
            } else {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Login failed: ${res.errorOrNull()}"
                    )
                }
            }
        }
    }

    fun register(email: String, pass: String, name: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = appContainer.authRepository.registerWithEmail(email, pass, UserRole.PARENT, name)
            if (res is AppResult.Success) {
                _uiState.update {
                    it.copy(
                        currentUser = res.data,
                        isAuthenticated = true,
                        isLoading = false,
                        successMessage = "Account created!"
                    )
                }
                observeDevices(res.data.uid)
            } else {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Registration failed: ${res.errorOrNull()}"
                    )
                }
            }
        }
    }

    fun quickParentDemoLogin() {
        login("parent.demo@familywellbeing.local", "securePassword123")
    }

    fun logout() {
        viewModelScope.launch {
            appContainer.authRepository.signOut()
            _uiState.update {
                it.copy(
                    currentUser = null,
                    isAuthenticated = false,
                    linkedDevices = emptyList()
                )
            }
        }
    }

    private fun observeDevices(parentUid: String) {
        viewModelScope.launch {
            appContainer.deviceRepository.observeParentLinkedDevices(parentUid).collectLatest { list ->
                _uiState.update { it.copy(linkedDevices = list) }
            }
        }
    }

    fun linkChildDevice(deviceId: String, childName: String) {
        val user = _uiState.value.currentUser ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val device = LinkedChildDevice(
                deviceId = deviceId.trim(),
                childUid = "child_${deviceId.trim()}",
                deviceName = childName.ifBlank { "Child Device" },
                enrolledAt = System.currentTimeMillis(),
                status = "ACTIVE"
            )
            val res = appContainer.deviceRepository.linkChildToParent(user.uid, device)
            if (res is AppResult.Success) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        successMessage = "Linked device: $childName"
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to link device: ${res.errorOrNull()}"
                    )
                }
            }
        }
    }

    fun clearMessages() {
        _uiState.update { it.copy(errorMessage = null, successMessage = null) }
    }

    class Factory(private val appContainer: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ParentDashboardViewModel(appContainer) as T
        }
    }
}
