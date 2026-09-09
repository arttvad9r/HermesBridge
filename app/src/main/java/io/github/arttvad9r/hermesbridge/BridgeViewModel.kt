package io.github.arttvad9r.hermesbridge

import android.app.Application
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

    private val _state = MutableStateFlow(
        BridgeUiState(health = healthRepository.snapshot())
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
