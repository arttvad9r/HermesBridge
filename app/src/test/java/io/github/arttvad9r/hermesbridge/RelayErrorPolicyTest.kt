package io.github.arttvad9r.hermesbridge

import io.github.arttvad9r.hermesbridge.protocol.ErrorPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RelayErrorPolicyTest {
    @Test
    fun knownRelayCodeUsesStableLocalTextAndIgnoresRemoteMessage() {
        val remoteMarker = "REMOTE_SECRET_MARKER_7fb274"
        val display = relayErrorDisplayMessage(
            ErrorPayload(
                code = "invalid_state",
                message = remoteMarker,
            )
        )

        assertEquals("Relay rejected the request in the current session state.", display)
        assertFalse(display.contains(remoteMarker))
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
