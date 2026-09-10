package io.github.arttvad9r.hermesbridge

import io.github.arttvad9r.hermesbridge.protocol.CommandRequestPayload
import io.github.arttvad9r.hermesbridge.protocol.CommandResultPayload
import io.github.arttvad9r.hermesbridge.protocol.ProtocolError
import io.github.arttvad9r.hermesbridge.security.ApprovalFingerprint
import java.util.LinkedHashMap
import kotlinx.coroutines.sync.Mutex

/**
 * Binds a relay request ID to one canonical command payload for the lifetime of this transport.
 *
 * `approval_required` is intentionally non-terminal: after the user resolves the exact local
 * ticket, Hermes may retry the same request ID and payload. Every other result becomes terminal
 * and is returned from memory for exact duplicates without executing the tool again.
 */
internal class CommandReplayGuard {
    private data class Entry(
        val fingerprint: String,
        val terminalResult: CommandResultPayload?,
    )

    private val mutex = Mutex()
    private val entries = LinkedHashMap<String, Entry>()

    suspend fun execute(
        request: CommandRequestPayload,
        action: suspend () -> CommandResultPayload,
    ): CommandResultPayload {
        if (!isValidRequestId(request.requestId)) {
            return failure(
                request.requestId.take(MAX_REQUEST_ID_LENGTH),
                "invalid_request_id",
                "requestId has an invalid format.",
            )
        }

        val fingerprint = ApprovalFingerprint.forRequest(request.tool, request.arguments)
        mutex.lock()
        try {
            val existing = entries[request.requestId]
            if (existing != null) {
                if (existing.fingerprint != fingerprint) {
                    return failure(
                        request.requestId,
                        "request_id_conflict",
                        "requestId is already bound to a different command payload.",
                    )
                }
                existing.terminalResult?.let { return it }
            }

            val result = action()
            val terminal = result.error?.code != APPROVAL_REQUIRED
            remember(
                requestId = request.requestId,
                entry = Entry(
                    fingerprint = fingerprint,
                    terminalResult = result.takeIf { terminal },
                ),
            )
            return result
        } finally {
            mutex.unlock()
        }
    }

    private fun remember(requestId: String, entry: Entry) {
        entries[requestId] = entry
        while (entries.size > MAX_ENTRIES) {
            val oldest = entries.entries.firstOrNull() ?: return
            entries.remove(oldest.key)
        }
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
    }
}
