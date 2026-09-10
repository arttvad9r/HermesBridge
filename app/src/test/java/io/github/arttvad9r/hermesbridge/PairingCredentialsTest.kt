package io.github.arttvad9r.hermesbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingCredentialsTest {
    @Test
    fun `resume succeeds when pairing and identity both exist`() {
        val store = FakePairingStore("device_123")
        val identity = FakeIdentity(hasKey = true)

        val result = PairingCredentialManager(store, identity).validateResume()

        assertTrue(result.isSuccess)
        assertEquals("device_123", store.deviceId())
        assertFalse(store.cleared)
    }

    @Test
    fun `resume rejects unpaired device without touching identity`() {
        val store = FakePairingStore(null)
        val identity = FakeIdentity(hasKey = true)

        val result = PairingCredentialManager(store, identity).validateResume()

        assertTrue(result.isFailure)
        assertEquals(0, identity.hasKeyChecks)
        assertFalse(store.cleared)
    }

    @Test
    fun `missing identity clears stale pairing and requires repair`() {
        val store = FakePairingStore("device_123")
        val identity = FakeIdentity(hasKey = false)

        val result = PairingCredentialManager(store, identity).validateResume()
        val error = result.exceptionOrNull() as DeviceIdentityUnavailableException

        assertEquals(DeviceIdentityFailureMode.REPAIR_REQUIRED, error.mode)
        assertTrue(requiresDeviceIdentityRepair(error))
        assertEquals(null, store.deviceId())
        assertTrue(store.cleared)
        assertFalse(identity.resetCalled)
    }

    @Test
    fun `temporary keystore check failure preserves pairing`() {
        val store = FakePairingStore("device_123")
        val failure = IllegalStateException("keystore busy")
        val identity = FakeIdentity(
            hasKey = true,
            hasKeyFailure = failure,
        )

        val result = PairingCredentialManager(store, identity).validateResume()
        val error = result.exceptionOrNull() as DeviceIdentityUnavailableException

        assertEquals(DeviceIdentityFailureMode.TRANSIENT, error.mode)
        assertFalse(requiresDeviceIdentityRepair(error))
        assertSame(failure, error.cause)
        assertEquals("device_123", store.deviceId())
        assertFalse(store.cleared)
        assertFalse(identity.resetCalled)
    }

    @Test
    fun `generic failures never trigger identity repair`() {
        assertFalse(requiresDeviceIdentityRepair(IllegalStateException("network failed")))
    }

    @Test
    fun `repair clears pairing and rotates identity`() {
        val store = FakePairingStore("device_123")
        val identity = FakeIdentity(hasKey = true)

        val result = PairingCredentialManager(store, identity).invalidateForRepair()

        assertTrue(result.isSuccess)
        assertEquals(null, store.deviceId())
        assertTrue(store.cleared)
        assertTrue(identity.resetCalled)
    }

    @Test
    fun `repair rotates identity even before a device id exists`() {
        val store = FakePairingStore(null)
        val identity = FakeIdentity(hasKey = true)

        val result = PairingCredentialManager(store, identity).invalidateForRepair()

        assertTrue(result.isSuccess)
        assertEquals(null, store.deviceId())
        assertTrue(store.cleared)
        assertTrue(identity.resetCalled)
    }

    @Test
    fun `pairing is still cleared when broken identity cannot be deleted`() {
        val store = FakePairingStore("device_123")
        val identity = FakeIdentity(
            hasKey = true,
            resetFailure = IllegalStateException("delete failed"),
        )

        val result = PairingCredentialManager(store, identity).invalidateForRepair()

        assertTrue(result.isFailure)
        assertEquals(null, store.deviceId())
        assertTrue(store.cleared)
        assertTrue(identity.resetCalled)
    }

    private class FakePairingStore(initialDeviceId: String?) : PairingRecordStore {
        private var currentDeviceId = initialDeviceId
        var cleared = false
            private set

        override fun deviceId(): String? = currentDeviceId

        override fun saveDeviceId(deviceId: String) {
            currentDeviceId = deviceId
        }

        override fun clear() {
            currentDeviceId = null
            cleared = true
        }
    }

    private class FakeIdentity(
        private val hasKey: Boolean,
        private val hasKeyFailure: Throwable? = null,
        private val resetFailure: Throwable? = null,
    ) : DeviceIdentity {
        var hasKeyChecks = 0
            private set
        var resetCalled = false
            private set

        override fun hasStoredKey(): Boolean {
            hasKeyChecks += 1
            hasKeyFailure?.let { throw it }
            return hasKey
        }

        override fun publicKeyBase64(): String = "unused"

        override fun sign(payload: ByteArray): String = "unused"

        override fun reset() {
            resetCalled = true
            resetFailure?.let { throw it }
        }
    }
}
