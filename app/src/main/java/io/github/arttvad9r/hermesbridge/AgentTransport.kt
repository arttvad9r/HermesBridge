package io.github.arttvad9r.hermesbridge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface AgentTransport {
    val connectionState: StateFlow<ConnectionState>

    suspend fun pair(code: String): Result<Unit>
    suspend fun resume(): Result<Unit>
    suspend fun disconnect(): Result<Unit>
}

/**
 * Fail-closed transport retained for tests and emergency fallback builds.
 */
class DisabledAgentTransport : AgentTransport {
    private val state = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = state

    override suspend fun pair(code: String): Result<Unit> =
        Result.failure(
            IllegalStateException("Remote relay transport is disabled in this build.")
        )

    override suspend fun resume(): Result<Unit> =
        Result.failure(
            IllegalStateException("Remote relay transport is disabled in this build.")
        )

    override suspend fun disconnect(): Result<Unit> = Result.success(Unit)
}
