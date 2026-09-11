package io.github.arttvad9r.hermesbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingRecordStateTest {
    @Test
    fun pendingRecordRoundTrips() {
        val record = PairingRecordSnapshot(
            deviceId = "device_12345678-1234-1234-1234-1234567890ab",
            phase = PairingRecordPhase.PENDING,
        )

        val encoded = encodePairingRecord(record)

        assertEquals("v1|pending|device_12345678-1234-1234-1234-1234567890ab", encoded)
        assertEquals(record, decodePairingRecord(encoded))
    }

    @Test
    fun activeRecordRoundTrips() {
        val record = PairingRecordSnapshot(
            deviceId = "device_123",
            phase = PairingRecordPhase.ACTIVE,
        )

        assertEquals(record, decodePairingRecord(encodePairingRecord(record)))
    }

    @Test
    fun malformedVersionOrPhaseFailsClosed() {
        assertNull(decodePairingRecord("v2|active|device_123"))
        assertNull(decodePairingRecord("v1|unknown|device_123"))
        assertNull(decodePairingRecord("active|device_123"))
    }

    @Test
    fun malformedDeviceIdentityFailsClosed() {
        assertNull(decodePairingRecord("v1|pending|device_"))
        assertNull(decodePairingRecord("v1|active|device_bad|suffix"))
        assertNull(decodePairingRecord("v1|active|device_bad/segment"))
        assertFalse(isValidPairingDeviceId("device_bad\nline"))
    }

    @Test
    fun deviceIdentityValidationMatchesRelayGeneratedShapeAndLegacyShortIds() {
        assertTrue(isValidPairingDeviceId("device_123"))
        assertTrue(isValidPairingDeviceId("device_12345678-1234-1234-1234-1234567890ab"))
        assertFalse(isValidPairingDeviceId("other_123"))
    }
}
