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
import kotlinx.coroutines.launch

data class DeviceDetailUiState(
    val deviceId: String = "",
    val parentUid: String = "",
    val deviceState: ChildDeviceFullState? = null,
    val isActionLoading: Boolean = false,
    val feedbackMessage: String? = null,
    val isUnlinked: Boolean = false
)

class DeviceDetailViewModel(
    private val appContainer: AppContainer,
    private val deviceId: String,
    private val parentUid: String,
    private val context: android.content.Context? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        DeviceDetailUiState(deviceId = deviceId, parentUid = parentUid)
    )
    val uiState: StateFlow<DeviceDetailUiState> = _uiState.asStateFlow()

    init {
        observeDevice()
        startP2PSync()
    }

    private fun startP2PSync() {
        if (context == null) return
        val p2pClient = com.example.data.p2p.ParentP2PClient.getInstance(context)
        viewModelScope.launch {
            while (kotlinx.coroutines.isActive) {
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
