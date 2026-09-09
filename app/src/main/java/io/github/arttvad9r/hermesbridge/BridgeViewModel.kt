package io.github.arttvad9r.hermesbridge

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class BridgeViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val healthRepository: DeviceHealthRepository =
        AndroidDeviceHealthRepository(application)
    private val pairingStore = PairingStore(application)
    private val treeStore = SafTreeStore(application)

    private val _state = MutableStateFlow(
        BridgeUiState(
            health = healthRepository.snapshot(),
            fileAccessConfigured = treeStore.treeUri() != null,
        )
    )
    val state: StateFlow<BridgeUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            BridgeRuntime.state.collectLatest { runtime ->
                _state.update { current ->
                    current.copy(
                        connectionState = runtime.connectionState,
                        pairingCode = if (runtime.connectionState == ConnectionState.CONNECTED) {
                            ""
                        } else {
                            current.pairingCode
                        },
                        message = runtime.message,
                    )
                }
            }
        }

        viewModelScope.launch {
            BridgeApprovalRuntime.pendingTickets.collectLatest { tickets ->
                _state.update { it.copy(pendingApprovals = tickets) }
            }
        }

        viewModelScope.launch {
            ShizukuRuntime.state.collectLatest { shizuku ->
                _state.update { it.copy(shizuku = shizuku) }
            }
        }

        BridgeApprovalRuntime.refresh()
        ShizukuRuntime.refresh()

        if (pairingStore.deviceId() != null) {
            runCatching { BridgeForegroundService.connect(app) }
                .onFailure { error ->
                    BridgeRuntime.update(
                        ConnectionState.ERROR,
                        error.message ?: "Не удалось запустить фоновое соединение.",
                    )
                }
        }
    }

    fun updatePairingCode(value: String) {
        _state.update {
            it.copy(
                pairingCode = value.uppercase().take(9),
                message = null,
            )
        }
    }

    fun refreshHealth() {
        _state.update { it.copy(health = healthRepository.snapshot()) }
    }

    fun approveAction(approvalId: String) {
        BridgeApprovalRuntime.approve(approvalId)
    }

    fun denyAction(approvalId: String) {
        BridgeApprovalRuntime.deny(approvalId)
    }

    fun requestShizukuPermission() {
        ShizukuRuntime.requestPermission()
    }

    fun refreshShizuku() {
        ShizukuRuntime.refresh()
    }

    fun grantFileTree(uri: Uri) {
        val readFlag = Intent.FLAG_GRANT_READ_URI_PERMISSION
        runCatching {
            app.contentResolver.takePersistableUriPermission(uri, readFlag)
            val previous = treeStore.treeUri()
            treeStore.save(uri)
            if (previous != null && previous != uri) {
                runCatching {
                    app.contentResolver.releasePersistableUriPermission(previous, readFlag)
                }
            }
        }.onSuccess {
            _state.update {
                it.copy(
                    fileAccessConfigured = true,
                    message = null,
                )
            }
        }.onFailure { error ->
            _state.update {
                it.copy(
                    fileAccessConfigured = treeStore.treeUri() != null,
                    message = error.message ?: "Не удалось сохранить доступ к выбранной папке.",
                )
            }
        }
    }

    fun revokeFileAccess() {
        val current = treeStore.treeUri()
        if (current != null) {
            runCatching {
                app.contentResolver.releasePersistableUriPermission(
                    current,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        treeStore.clear()
        _state.update {
            it.copy(
                fileAccessConfigured = false,
                message = null,
            )
        }
    }

    fun pair() {
        val code = PairingCodeValidator.normalize(_state.value.pairingCode)
        if (!PairingCodeValidator.isValid(code)) {
            _state.update { it.copy(message = "Код должен иметь формат XXXX-XXXX.") }
            return
        }

        BridgeRuntime.update(ConnectionState.PAIRING)
        runCatching { BridgeForegroundService.pair(app, code) }
            .onFailure { error ->
                BridgeRuntime.update(
                    ConnectionState.ERROR,
                    error.message ?: "Не удалось запустить привязку устройства.",
                )
            }
    }
}
