package com.example.core.permissions

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * UI State for transparent runtime permission requests and disclosures.
 */
data class PermissionItemState(
    val type: PermissionType,
    val isGranted: Boolean,
    val isSpecialAccess: Boolean = type.isSpecialAccess
)

data class PermissionsUiState(
    val permissions: List<PermissionItemState> = emptyList(),
    val pendingDisclosure: PermissionType? = null,
    val showRationaleDialog: Boolean = false,
    val lastGrantedType: PermissionType? = null,
    val lastDeniedType: PermissionType? = null
)

/**
 * ViewModel managing runtime permissions, ensuring transparent user disclosure before every request.
 */
class PermissionViewModel(
    private val permissionManager: PermissionManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(PermissionsUiState())
    val uiState: StateFlow<PermissionsUiState> = _uiState.asStateFlow()

    init {
        refreshPermissionStates()
    }

    /**
     * Refreshes the granted state of all permissions from [PermissionManager].
     */
    fun refreshPermissionStates() {
        val items = PermissionType.values().map { type ->
            PermissionItemState(
                type = type,
                isGranted = permissionManager.isPermissionGranted(type)
            )
        }
        _uiState.update {
            it.copy(permissions = items)
        }
    }

    /**
     * Called when the user clicks to request or configure a permission.
     * Sets [pendingDisclosure] and shows transparent rationale disclosure dialog first.
     */
    fun onPermissionRequested(type: PermissionType) {
        if (permissionManager.isPermissionGranted(type)) {
            // Already granted, refresh and do not prompt
            refreshPermissionStates()
            return
        }

        _uiState.update {
            it.copy(
                pendingDisclosure = type,
                showRationaleDialog = true
            )
        }
    }

    /**
     * Called after user reads and confirms the upfront transparent disclosure.
     * Clears the dialog state and indicates ready to launch system dialog.
     */
    fun onDisclosureConfirmed(): PermissionType? {
        val type = _uiState.value.pendingDisclosure
        _uiState.update {
            it.copy(
                showRationaleDialog = false,
                pendingDisclosure = null
            )
        }
        return type
    }

    /**
     * Called if user dismisses or cancels the upfront disclosure dialog.
     */
    fun onDisclosureDismissed() {
        _uiState.update {
            it.copy(
                showRationaleDialog = false,
                pendingDisclosure = null
            )
        }
    }

    /**
     * Handles result callback from Android permission launcher.
     */
    fun onPermissionResult(type: PermissionType, isGranted: Boolean) {
        refreshPermissionStates()
        _uiState.update {
            it.copy(
                lastGrantedType = if (isGranted) type else null,
                lastDeniedType = if (!isGranted) type else null
            )
        }
    }

    class Factory(
        private val permissionManager: PermissionManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return PermissionViewModel(permissionManager) as T
        }
    }
}
