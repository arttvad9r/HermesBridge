package io.github.arttvad9r.hermesbridge

import io.github.arttvad9r.hermesbridge.protocol.BridgeProtocol
import io.github.arttvad9r.hermesbridge.protocol.CommandResultPayload
import io.github.arttvad9r.hermesbridge.protocol.ProtocolError
import kotlinx.serialization.encodeToString

/**
 * Final size backstop for Android -> relay command results.
 *
 * Individual tools should still expose useful bounded/truncated projections where possible. This
 * guard exists so a future handler cannot accidentally exceed the 256 KiB control-channel ceiling
 * and turn one response into a reconnect/retry loop. The payload budget keeps a large fixed margin
 * for the surrounding WireEnvelope fields.
 */
internal fun enforceRemoteCommandResultBudget(result: CommandResultPayload): CommandResultPayload {
    if (remoteCommandResultPayloadBytes(result) <= MAX_REMOTE_COMMAND_RESULT_PAYLOAD_BYTES) {
        return result
    }

    return CommandResultPayload(
        requestId = result.requestId,
        ok = false,
        error = ProtocolError(
            code = RESULT_TOO_LARGE_ERROR_CODE,
            message = remoteSafeCommandErrorMessage(RESULT_TOO_LARGE_ERROR_CODE),
        ),
    )
}

internal fun remoteCommandResultPayloadBytes(result: CommandResultPayload): Long =
    BridgeProtocol.json
        .encodeToString(result)
        .toByteArray(Charsets.UTF_8)
        .size
        .toLong()

internal const val REMOTE_COMMAND_RESULT_ENVELOPE_MARGIN_BYTES = 32L * 1024L
internal const val MAX_REMOTE_COMMAND_RESULT_PAYLOAD_BYTES =
    RELAY_WEBSOCKET_MAX_FRAME_BYTES - REMOTE_COMMAND_RESULT_ENVELOPE_MARGIN_BYTES
internal const val RESULT_TOO_LARGE_ERROR_CODE = "result_too_large"
