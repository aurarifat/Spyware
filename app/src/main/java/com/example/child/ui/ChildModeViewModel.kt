package com.example.child.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.child.service.FamilyMonitorForegroundService
import com.example.core.common.AppResult
import com.example.core.di.AppContainer
import com.example.core.permissions.PermissionType
import com.example.domain.model.AppUsageEntry
import com.example.domain.model.CallEntry
import com.example.domain.model.DeviceStatus
import com.example.domain.model.MediaEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChildModeUiState(
    val deviceId: String = "",
    val deviceName: String = "Child's Phone",
    val childUid: String = "",
    val linkedParentUid: String = "",
    val isConsentGiven: Boolean = false,
    val isServiceRunning: Boolean = false,
    val isLoading: Boolean = false,
    val status: DeviceStatus = DeviceStatus(),
    val calls: List<CallEntry> = emptyList(),
    val media: List<MediaEntry> = emptyList(),
    val permissions: Map<String, Boolean> = emptyMap(),
    val message: String? = null,
    val isFlashlightActive: Boolean = false,
    val localIpAddress: String = "",
    val p2pPort: Int = 8888,
    val pairingCode: String = ""
)

class ChildModeViewModel(
    private val appContainer: AppContainer,
    private val appContext: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChildModeUiState())
    val uiState: StateFlow<ChildModeUiState> = _uiState.asStateFlow()

    init {
        val devId = appContainer.deviceIdManager.getOrCreateDeviceId()
        val consent = appContainer.localPrefs.isConsentGiven()
        val parentUid = appContainer.localPrefs.getLinkedParentUid() ?: ""
        val name = appContainer.localPrefs.getChildDeviceName()

        val ip = com.example.data.p2p.P2PNetworkUtils.getLocalIpAddress()
        val code = com.example.data.p2p.P2PNetworkUtils.generateShortPairingCode(devId)

        _uiState.update {
            it.copy(
                deviceId = devId,
                deviceName = name,
                isConsentGiven = consent,
                linkedParentUid = parentUid,
                localIpAddress = ip,
                pairingCode = code
            )
        }

        initializeChildAuth()
        observeDeviceCommands(devId)
        refreshLocalStatus()
        loadRecentMedia()
        startP2PHub()
    }

    private fun startP2PHub() {
        try {
            com.example.data.p2p.ChildP2PServer.getInstance(appContext, appContainer).start()
        } catch (e: Exception) {
            // Log and ignore
        }
    }

    private fun initializeChildAuth() {
        viewModelScope.launch {
            val authRes = appContainer.authRepository.signInAnonymously()
            if (authRes is AppResult.Success) {
                _uiState.update { it.copy(childUid = authRes.data.uid) }
            }
        }
    }

    private fun observeDeviceCommands(deviceId: String) {
        viewModelScope.launch {
            appContainer.commandRepository.observeFlashlightCommand(deviceId).collectLatest { cmd ->
                _uiState.update { it.copy(isFlashlightActive = cmd?.enabled == true) }
            }
        }
    }

    fun setConsentGiven(given: Boolean) {
        appContainer.localPrefs.setConsentGiven(given)
        _uiState.update { it.copy(isConsentGiven = given) }
    }

    fun setDeviceName(name: String) {
        appContainer.localPrefs.setChildDeviceName(name)
        _uiState.update { it.copy(deviceName = name) }
    }

    fun linkToParent(parentUid: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val state = _uiState.value
            val enrollRes = appContainer.enrollChildUseCase(
                deviceId = state.deviceId,
                childUid = state.childUid.ifBlank { "child_${state.deviceId}" },
                parentUid = parentUid.trim(),
                parentDisplayName = "Parent ($parentUid)",
                deviceName = state.deviceName
            )

            if (enrollRes is AppResult.Success) {
                appContainer.localPrefs.setLinkedParentUid(parentUid.trim())
                _uiState.update {
                    it.copy(
                        linkedParentUid = parentUid.trim(),
                        isLoading = false,
                        message = "Successfully linked to parent!"
                    )
                }
                startSupervisionService()
                syncNow()
            } else {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        message = "Could not link to parent: ${enrollRes.errorOrNull()}"
                    )
                }
            }
        }
    }

    fun startSupervisionService() {
        val state = _uiState.value
        FamilyMonitorForegroundService.startService(
            appContext,
            state.deviceId,
            state.linkedParentUid.ifBlank { "Parent Account" }
        )
        _uiState.update { it.copy(isServiceRunning = true) }
    }

    fun stopSupervisionService() {
        FamilyMonitorForegroundService.stopService(appContext)
        _uiState.update { it.copy(isServiceRunning = false) }
    }

    fun revokeSupervision() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            stopSupervisionService()
            val parentUid = _uiState.value.linkedParentUid
            val devId = _uiState.value.deviceId

            if (parentUid.isNotBlank()) {
                appContainer.deviceRepository.unlinkDevice(parentUid, devId)
            }
            appContainer.localPrefs.setLinkedParentUid(null)
            appContainer.localPrefs.setConsentGiven(false)

            _uiState.update {
                it.copy(
                    linkedParentUid = "",
                    isConsentGiven = false,
                    isServiceRunning = false,
                    isLoading = false,
                    message = "Supervision revoked. Data synchronization stopped."
                )
            }
        }
    }

    fun refreshLocalStatus() {
        val permissions = appContainer.permissionManager.getPermissionMap()
        _uiState.update { it.copy(permissions = permissions) }
        loadRecentMedia()
    }

    fun loadRecentMedia() {
        viewModelScope.launch {
            val mediaRes = appContainer.mediaRepository.getRecentMedia(12)
            if (mediaRes is AppResult.Success) {
                _uiState.update { it.copy(media = mediaRes.data) }
            }
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val res = appContainer.syncChildStatusUseCase(_uiState.value.deviceId)
            if (res is AppResult.Success) {
                _uiState.update {
                    it.copy(
                        status = res.data,
                        isLoading = false,
                        permissions = appContainer.permissionManager.getPermissionMap(),
                        message = "Status synchronized"
                    )
                }
            } else {
                _uiState.update { it.copy(isLoading = false, message = "Sync error: ${res.errorOrNull()}") }
            }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    class Factory(
        private val appContainer: AppContainer,
        private val appContext: Context
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ChildModeViewModel(appContainer, appContext) as T
        }
    }
}
