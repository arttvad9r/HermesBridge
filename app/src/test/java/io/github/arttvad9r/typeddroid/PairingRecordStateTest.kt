package io.github.arttvad9r.typeddroid

import io.github.arttvad9r.typeddroid.security.AgentTrustMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingRecordStateTest {
    @Test
    fun pendingSafeRecordRoundTrips() {
        val record = PairingRecordSnapshot(
            deviceId = "device_12345678-1234-1234-1234-1234567890ab",
            phase = PairingRecordPhase.PENDING,
        )

        val encoded = encodePairingRecord(record)

        assertEquals("v2|pending|safe|device_12345678-1234-1234-1234-1234567890ab", encoded)
        assertEquals(record, decodePairingRecord(encoded))
    }

    @Test
    fun trustedRecordRoundTrips() {
        val record = PairingRecordSnapshot(
            deviceId = "device_123",
            phase = PairingRecordPhase.ACTIVE,
            trustMode = AgentTrustMode.TRUSTED,
        )

        assertEquals("v2|active|trusted|device_123", encodePairingRecord(record))
        assertEquals(record, decodePairingRecord(encodePairingRecord(record)))
    }

    @Test
    fun legacyV1RecordMigratesFailSafeToSafeTrust() {
        assertEquals(
            PairingRecordSnapshot(
                deviceId = "device_123",
                phase = PairingRecordPhase.ACTIVE,
                trustMode = AgentTrustMode.SAFE,
            ),
            decodePairingRecord("v1|active|device_123"),
        )
    }

    @Test
    fun malformedVersionPhaseOrTrustFailsClosed() {
        assertNull(decodePairingRecord("v3|active|safe|device_123"))
        assertNull(decodePairingRecord("v2|unknown|safe|device_123"))
        assertNull(decodePairingRecord("v2|active|owner|device_123"))
        assertNull(decodePairingRecord("active|device_123"))
    }

    @Test
    fun malformedDeviceIdentityFailsClosed() {
        assertNull(decodePairingRecord("v1|pending|device_"))
        assertNull(decodePairingRecord("v2|active|safe|device_bad|suffix"))
        assertNull(decodePairingRecord("v2|active|safe|device_bad/segment"))
        assertFalse(isValidPairingDeviceId("device_bad\nline"))
    }

    @Test
    fun deviceIdentityValidationMatchesRelayGeneratedShapeAndLegacyShortIds() {
        assertTrue(isValidPairingDeviceId("device_123"))
        assertTrue(isValidPairingDeviceId("device_12345678-1234-1234-1234-1234567890ab"))
        assertFalse(isValidPairingDeviceId("other_123"))
    }
}
