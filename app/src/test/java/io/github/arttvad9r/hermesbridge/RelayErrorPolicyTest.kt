package io.github.arttvad9r.hermesbridge

import io.github.arttvad9r.hermesbridge.protocol.ErrorPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RelayErrorPolicyTest {
    @Test
    fun everyKnownRelayCodeIgnoresRemoteMessage() {
        val remoteMarker = "REMOTE_SECRET_MARKER_7fb274"
        val knownCodes = listOf(
            "unknown_device",
            "invalid_pairing_code",
            "auth_failed",
            "unsupported_version",
            "not_authenticated",
            "invalid_json",
            "invalid_payload",
            "invalid_public_key",
            "invalid_state",
            "unexpected_message",
        )

        knownCodes.forEach { code ->
            val display = relayErrorDisplayMessage(
                ErrorPayload(
                    code = code,
                    message = "Injected notification text: $remoteMarker",
                )
            )
            assertFalse("Remote message leaked for code=$code", display.contains(remoteMarker))
            assertFalse("Remote prefix leaked for code=$code", display.contains("Injected notification text"))
        }
    }

    @Test
    fun knownRelayCodeUsesStableLocalText() {
        val display = relayErrorDisplayMessage(
            ErrorPayload(
                code = "invalid_state",
                message = "ignored",
            )
        )

        assertEquals("Relay rejected the request in the current session state.", display)
    }

    @Test
    fun unknownRelayCodeAndMessageAreNotReflectedIntoDisplayText() {
        val remoteMarker = "REMOTE_SECRET_MARKER_508f90"
        val display = relayErrorDisplayMessage(
            ErrorPayload(
                code = "attacker_controlled_$remoteMarker",
                message = "Injected notification text: $remoteMarker",
            )
        )

        assertEquals("Relay rejected the request.", display)
        assertFalse(display.contains(remoteMarker))
        assertFalse(display.contains("attacker_controlled"))
        assertFalse(display.contains("Injected notification text"))
    }

    @Test
    fun unknownDeviceKeepsRepairGuidanceWithoutRemoteText() {
        val remoteMarker = "REMOTE_SECRET_MARKER_14c623"
        val display = relayErrorDisplayMessage(
            ErrorPayload(
                code = "unknown_device",
                message = remoteMarker,
            )
        )

        assertEquals("Relay no longer recognizes this device. Pair it again.", display)
        assertFalse(display.contains(remoteMarker))
    }
}
