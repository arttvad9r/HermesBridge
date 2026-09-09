package io.github.arttvad9r.hermesbridge

interface AgentTransport {
    suspend fun pair(code: String): Result<Unit>
    suspend fun disconnect(): Result<Unit>
}

/**
 * Deliberately fails closed until the authenticated outbound relay transport exists.
 * The UI and domain flow can be exercised without pretending that the phone is paired.
 */
class DisabledAgentTransport : AgentTransport {
    override suspend fun pair(code: String): Result<Unit> =
        Result.failure(
            IllegalStateException("Remote relay transport is not implemented in this build.")
        )

    override suspend fun disconnect(): Result<Unit> = Result.success(Unit)
}
