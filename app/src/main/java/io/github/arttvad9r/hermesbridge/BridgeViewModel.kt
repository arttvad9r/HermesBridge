package io.github.arttvad9r.hermesbridge

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class BridgeViewModel(application: Application) : AndroidViewModel(application) {
    private val healthRepository: DeviceHealthRepository =
        AndroidDeviceHealthRepository(application)
    private val transport: AgentTransport = DisabledAgentTransport()

    private val _state = MutableStateFlow(
        BridgeUiState(health = healthRepository.snapshot())
    )
    val state: StateFlow<BridgeUiState> = _state.asStateFlow()

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

        viewModelScope.launch {
            _state.update {
                it.copy(
                    connectionState = ConnectionState.PAIRING,
                    message = null,
                )
            }

            transport.pair(code)
                .onSuccess {
                    _state.update {
                        it.copy(
                            connectionState = ConnectionState.CONNECTED,
                            message = null,
                        )
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            connectionState = ConnectionState.ERROR,
                            message = error.message,
                        )
                    }
                }
        }
    }
}
