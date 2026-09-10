package io.github.arttvad9r.hermesbridge

import io.github.arttvad9r.hermesbridge.protocol.CommandRequestPayload
import io.github.arttvad9r.hermesbridge.protocol.CommandResultPayload
import io.github.arttvad9r.hermesbridge.protocol.ProtocolError
import io.github.arttvad9r.hermesbridge.security.ApprovalFingerprint
import java.util.LinkedHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Binds a relay request ID to one canonical command payload for the lifetime of this transport.
 *
 * Exact terminal results are retained only for the current non-idempotent tool set. Read-only
 * commands may be re-read safely, while a reused request ID can never be rebound to a different
 * payload while its entry remains in this bounded table.
 *
 * `approval_required` is intentionally non-terminal: after the user resolves the exact local
 * ticket, Hermes may retry the same request ID and payload. Concurrent duplicates of one request
 * ID share a single in-flight execution; unrelated request IDs are never serialized behind it.
 */
internal class CommandReplayGuard {
    internal data class Outcome(
        val result: CommandResultPayload,
        val shouldAudit: Boolean,
    )

    private data class Entry(
        val fingerprint: String,
        val terminalResult: CommandResultPayload? = null,
        val inFlight: CompletableDeferred<CommandResultPayload>? = null,
    )

    private sealed interface Decision {
        data class Return(val outcome: Outcome) : Decision
        data class Await(val deferred: CompletableDeferred<CommandResultPayload>) : Decision
        data class Run(val deferred: CompletableDeferred<CommandResultPayload>) : Decision
    }

    private val mutex = Mutex()
    private val entries = LinkedHashMap<String, Entry>()

    suspend fun execute(
        request: CommandRequestPayload,
        action: suspend () -> CommandResultPayload,
    ): Outcome {
        if (!isValidRequestId(request.requestId)) {
            return Outcome(
                result = failure(
                    request.requestId.take(MAX_REQUEST_ID_LENGTH),
                    "invalid_request_id",
                    "requestId has an invalid format.",
                ),
                shouldAudit = true,
            )
        }

        // ApprovalFingerprint intentionally rejects blank tool names because valid approval tickets
        // can never use them. A blank command is already non-executable/default-deny, so preserve
        // the router's structured unknown-tool response instead of letting fingerprint validation
        // turn malformed input into a transport exception.
        if (request.tool.isBlank()) {
            return Outcome(action(), shouldAudit = true)
        }

        val fingerprint = ApprovalFingerprint.forRequest(request.tool, request.arguments)
        val decision = mutex.withLock {
            val existing = entries[request.requestId]
            when {
                existing == null -> {
                    if (!makeRoomLocked()) {
                        Decision.Return(
                            Outcome(
                                result = failure(
                                    request.requestId,
                                    "request_replay_busy",
                                    "Too many command requests are currently in flight.",
                                ),
                                shouldAudit = true,
                            )
                        )
                    } else {
                        val deferred = CompletableDeferred<CommandResultPayload>()
                        entries[request.requestId] = Entry(
                            fingerprint = fingerprint,
                            inFlight = deferred,
                        )
                        Decision.Run(deferred)
                    }
                }

                existing.fingerprint != fingerprint -> Decision.Return(
                    Outcome(
                        result = failure(
                            request.requestId,
                            "request_id_conflict",
                            "requestId is already bound to a different command payload.",
                        ),
                        shouldAudit = true,
                    )
                )

                existing.terminalResult != null -> Decision.Return(
                    Outcome(existing.terminalResult, shouldAudit = false)
                )

                existing.inFlight != null -> Decision.Await(existing.inFlight)

                else -> {
                    val deferred = CompletableDeferred<CommandResultPayload>()
                    entries[request.requestId] = existing.copy(inFlight = deferred)
                    Decision.Run(deferred)
                }
            }
        }

        return when (decision) {
            is Decision.Return -> decision.outcome
            is Decision.Await -> Outcome(decision.deferred.await(), shouldAudit = false)
            is Decision.Run -> executeNew(request, fingerprint, decision.deferred, action)
        }
    }

    private suspend fun executeNew(
        request: CommandRequestPayload,
        fingerprint: String,
        deferred: CompletableDeferred<CommandResultPayload>,
        action: suspend () -> CommandResultPayload,
    ): Outcome {
        return try {
            val result = action()
            val cacheTerminal =
                request.tool in NON_IDEMPOTENT_TOOLS && result.error?.code != APPROVAL_REQUIRED
            mutex.withLock {
                val current = entries[request.requestId]
                if (current?.fingerprint == fingerprint && current.inFlight === deferred) {
                    entries[request.requestId] = Entry(
                        fingerprint = fingerprint,
                        terminalResult = result.takeIf { cacheTerminal },
                    )
                }
            }
            deferred.complete(result)
            Outcome(result, shouldAudit = true)
        } catch (error: Throwable) {
            mutex.withLock {
                val current = entries[request.requestId]
                if (current?.fingerprint == fingerprint && current.inFlight === deferred) {
                    entries[request.requestId] = Entry(fingerprint = fingerprint)
                }
            }
            deferred.completeExceptionally(error)
            throw error
        }
    }

    private fun makeRoomLocked(): Boolean {
        if (entries.size < MAX_ENTRIES) return true
        val removable = entries.entries.firstOrNull { it.value.inFlight == null } ?: return false
        entries.remove(removable.key)
        return true
    }

    private fun isValidRequestId(value: String): Boolean =
        value.length in 1..MAX_REQUEST_ID_LENGTH && REQUEST_ID_REGEX.matches(value)

    private fun failure(requestId: String, code: String, message: String) =
        CommandResultPayload(
            requestId = requestId,
            ok = false,
            error = ProtocolError(code, message),
        )

    private companion object {
        const val MAX_ENTRIES = 200
        const val MAX_REQUEST_ID_LENGTH = 128
        const val APPROVAL_REQUIRED = "approval_required"
        val REQUEST_ID_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
        val NON_IDEMPOTENT_TOOLS = setOf(
            "files.delete",
            "apps.install",
            "apps.uninstall",
            "apps.forceStop",
            "apps.revokePermission",
        )
    }
}
