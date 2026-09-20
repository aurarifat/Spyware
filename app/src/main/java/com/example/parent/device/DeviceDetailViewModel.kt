package com.example.parent.device

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.core.common.AppResult
import com.example.core.di.AppContainer
import com.example.domain.model.ChildDeviceFullState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class DeviceDetailUiState(
    val deviceId: String = "",
    val parentUid: String = "",
    val deviceState: ChildDeviceFullState? = null,
    val isActionLoading: Boolean = false,
    val feedbackMessage: String? = null,
    val isUnlinked: Boolean = false,
    val p2pAddress: String = ""
)

class DeviceDetailViewModel(
    private val appContainer: AppContainer,
    val deviceId: String,
    val parentUid: String,
    private val context: android.content.Context? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        DeviceDetailUiState(
            deviceId = deviceId,
            parentUid = parentUid,
            p2pAddress = if (context != null) com.example.data.p2p.ParentP2PClient.getInstance(context).getDeviceAddress(deviceId) ?: "" else ""
        )
    )
    val uiState: StateFlow<DeviceDetailUiState> = _uiState.asStateFlow()

    init {
        observeDevice()
        startP2PSync()
    }

    fun updateP2PAddress(address: String) {
        val trimmed = address.trim()
        val normalized = if (trimmed.isNotBlank() && !trimmed.contains(":")) "$trimmed:8888" else trimmed
        if (context != null) {
            val p2pClient = com.example.data.p2p.ParentP2PClient.getInstance(context)
            p2pClient.setDeviceAddress(deviceId, normalized)
        }
        _uiState.update { it.copy(p2pAddress = normalized, feedbackMessage = "Device address updated: $normalized") }
    }

    private fun startP2PSync() {
        if (context == null) return
        val p2pClient = com.example.data.p2p.ParentP2PClient.getInstance(context)
        viewModelScope.launch {
            while (isActive) {
                try {
                    val address = p2pClient.getDeviceAddress(deviceId) ?: "127.0.0.1:8888"
                    val res = p2pClient.fetchChildStatus(address)
                    if (res is AppResult.Success) {
                        appContainer.deviceRepository.updateLocalStateFromP2P(res.data)
                    }
                } catch (e: Exception) {
                    // Ignore transient network errors
                }
                kotlinx.coroutines.delay(5000)
            }
        }
    }

    private fun observeDevice() {
        viewModelScope.launch {
            appContainer.deviceRepository.observeDeviceFullState(deviceId).collectLatest { state ->
                _uiState.update { it.copy(deviceState = state) }
            }
        }
    }

    fun toggleFlashlight(enable: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(isActionLoading = true) }
            val res = appContainer.commandRepository.sendFlashlightCommand(
                deviceId = deviceId,
                parentUid = parentUid,
                enabled = enable
            )
            if (res is AppResult.Success) {
                _uiState.update {
                    it.copy(
                        isActionLoading = false,
                        feedbackMessage = if (enable) "Flashlight turn-on command sent" else "Flashlight turn-off command sent"
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        isActionLoading = false,
                        feedbackMessage = "Failed to send command: ${res.errorOrNull()}"
                    )
                }
            }
        }
    }

    fun refreshStatus() {
        viewModelScope.launch {
            _uiState.update { it.copy(isActionLoading = true) }
            val res = appContainer.commandRepository.sendRefreshCommand(
                deviceId = deviceId,
                parentUid = parentUid
            )
            if (res is AppResult.Success) {
                _uiState.update {
                    it.copy(
                        isActionLoading = false,
                        feedbackMessage = "Refresh command sent to device"
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        isActionLoading = false,
                        feedbackMessage = "Failed to request refresh: ${res.errorOrNull()}"
                    )
                }
            }
        }
    }

    fun unlinkDevice() {
        viewModelScope.launch {
            _uiState.update { it.copy(isActionLoading = true) }
            val res = appContainer.deviceRepository.unlinkDevice(parentUid, deviceId)
            if (res is AppResult.Success) {
                _uiState.update {
                    it.copy(
                        isActionLoading = false,
                        isUnlinked = true,
                        feedbackMessage = "Device unlinked successfully"
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        isActionLoading = false,
                        feedbackMessage = "Unlink failed: ${res.errorOrNull()}"
                    )
                }
            }
        }
    }

    fun clearFeedback() {
        _uiState.update { it.copy(feedbackMessage = null) }
    }

    class Factory(
        private val appContainer: AppContainer,
        private val deviceId: String,
        private val parentUid: String,
        private val context: android.content.Context? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DeviceDetailViewModel(appContainer, deviceId, parentUid, context) as T
        }
    }
}
