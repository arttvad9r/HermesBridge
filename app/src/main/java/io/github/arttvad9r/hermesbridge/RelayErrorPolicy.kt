package io.github.arttvad9r.hermesbridge

import io.github.arttvad9r.hermesbridge.protocol.ErrorPayload

/**
 * Maps relay protocol failures to local user-facing text.
 *
 * Both fields of [ErrorPayload] arrive from the network. The message is intentionally never used
 * for display, and the code is matched only against this fixed allowlist. This prevents a
 * compromised or incompatible relay from injecting arbitrary text into Android UI/notifications.
 */
internal fun relayErrorDisplayMessage(error: ErrorPayload): String = when (error.code) {
    "unknown_device" -> "Relay no longer recognizes this device. Pair it again."
    "invalid_pairing_code" -> "Pairing code is invalid or expired."
    "auth_failed" -> "Relay authentication failed."
    "unsupported_version" -> "Relay protocol version is not supported."
    "not_authenticated" -> "Relay rejected an unauthenticated request."
    "invalid_json",
    "invalid_payload",
    -> "Relay rejected invalid protocol data."
    "invalid_public_key" -> "Relay rejected the device public key."
    "invalid_state" -> "Relay rejected the request in the current session state."
    "unexpected_message" -> "Relay rejected an unexpected protocol message."
    else -> "Relay rejected the request."
}
