package io.github.arttvad9r.hermesbridge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BridgeRuntimeState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val message: String? = null,
)

object BridgeRuntime {
    private val mutableState = MutableStateFlow(BridgeRuntimeState())
    val state: StateFlow<BridgeRuntimeState> = mutableState.asStateFlow()

    fun update(
        connectionState: ConnectionState,
        message: String? = null,
    ) {
        mutableState.value = BridgeRuntimeState(connectionState, message)
    }
}
