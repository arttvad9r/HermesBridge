package io.github.arttvad9r.hermesbridge

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceIdentityTest {
    @Test
    fun `fresh identity generates current alias and exposes its public key`() {
        val backend = FakeDeviceKeyStoreBackend()
        val identity = AndroidKeystoreDeviceIdentity(backend, CURRENT_ALIAS)

        assertFalse(identity.hasStoredKey())
        val encoded = Base64.getDecoder().decode(identity.publicKeyBase64())

        assertTrue(identity.hasStoredKey())
        assertEquals(listOf(CURRENT_ALIAS), backend.generatedAliases)
        assertArrayEquals(backend.keyPair(CURRENT_ALIAS).public.encoded, encoded)
    }

    @Test
    fun `public key generation is idempotent for an existing alias`() {
        val backend = FakeDeviceKeyStoreBackend().apply { seed(CURRENT_ALIAS) }
        val identity = AndroidKeystoreDeviceIdentity(backend, CURRENT_ALIAS)

        identity.publicKeyBase64()
        identity.publicKeyBase64()

        assertTrue(backend.generatedAliases.isEmpty())
    }

    @Test
    fun `sign uses existing key and produces a verifiable signature`() {
        val backend = FakeDeviceKeyStoreBackend().apply { seed(CURRENT_ALIAS) }
        val identity = AndroidKeystoreDeviceIdentity(backend, CURRENT_ALIAS)
        val payload = "hermes-auth-challenge".toByteArray()

        val signatureBytes = Base64.getDecoder().decode(identity.sign(payload))
        val verified = Signature.getInstance("SHA256withECDSA").run {
            initVerify(backend.keyPair(CURRENT_ALIAS).public)
            update(payload)
            verify(signatureBytes)
        }

        assertTrue(verified)
        assertTrue(backend.generatedAliases.isEmpty())
    }

    @Test
    fun `missing key during resume requires repair and is never silently regenerated`() {
        val backend = FakeDeviceKeyStoreBackend()
        val identity = AndroidKeystoreDeviceIdentity(backend, CURRENT_ALIAS)

        val error = runCatching { identity.sign(byteArrayOf(1, 2, 3)) }
            .exceptionOrNull() as DeviceIdentityUnavailableException

        assertEquals(DeviceIdentityFailureMode.REPAIR_REQUIRED, error.mode)
        assertTrue(backend.generatedAliases.isEmpty())
    }

    @Test
    fun `legacy alias alone does not satisfy a new identity version`() {
        val backend = FakeDeviceKeyStoreBackend().apply { seed(LEGACY_ALIAS) }
        val identity = AndroidKeystoreDeviceIdentity(backend, CURRENT_ALIAS)

        assertFalse(identity.hasStoredKey())
        val error = runCatching { identity.sign(byteArrayOf(4, 5, 6)) }
            .exceptionOrNull() as DeviceIdentityUnavailableException

        assertEquals(DeviceIdentityFailureMode.REPAIR_REQUIRED, error.mode)
        assertFalse(backend.containsAlias(CURRENT_ALIAS))
        assertTrue(backend.containsAlias(LEGACY_ALIAS))
    }

    @Test
    fun `unusable private entry requires repair`() {
        val backend = FakeDeviceKeyStoreBackend().apply {
            seed(CURRENT_ALIAS)
            returnMissingPrivateEntry = true
        }
        val identity = AndroidKeystoreDeviceIdentity(backend, CURRENT_ALIAS)

        val error = runCatching { identity.sign(byteArrayOf(7)) }
            .exceptionOrNull() as DeviceIdentityUnavailableException

        assertEquals(DeviceIdentityFailureMode.REPAIR_REQUIRED, error.mode)
    }

    @Test
    fun `permanently invalidated backend key requires repair`() {
        val backend = FakeDeviceKeyStoreBackend().apply {
            seed(CURRENT_ALIAS)
            signFailure = DeviceKeyInvalidatedException()
        }
        val identity = AndroidKeystoreDeviceIdentity(backend, CURRENT_ALIAS)

        val error = runCatching { identity.sign(byteArrayOf(8)) }
            .exceptionOrNull() as DeviceIdentityUnavailableException

        assertEquals(DeviceIdentityFailureMode.REPAIR_REQUIRED, error.mode)
        assertTrue(requiresDeviceIdentityRepair(error))
    }

    @Test
    fun `temporary signing failure preserves transient classification`() {
        val failure = IllegalStateException("keystore service unavailable")
        val backend = FakeDeviceKeyStoreBackend().apply {
            seed(CURRENT_ALIAS)
            signFailure = failure
        }
        val identity = AndroidKeystoreDeviceIdentity(backend, CURRENT_ALIAS)

        val error = runCatching { identity.sign(byteArrayOf(9)) }
            .exceptionOrNull() as DeviceIdentityUnavailableException

        assertEquals(DeviceIdentityFailureMode.TRANSIENT, error.mode)
        assertSame(failure, error.cause)
        assertFalse(requiresDeviceIdentityRepair(error))
    }

    @Test
    fun `missing certificate after key creation requires repair`() {
        val backend = FakeDeviceKeyStoreBackend().apply {
            seed(CURRENT_ALIAS)
            returnMissingCertificate = true
        }
        val identity = AndroidKeystoreDeviceIdentity(backend, CURRENT_ALIAS)

        val error = runCatching { identity.publicKeyBase64() }
            .exceptionOrNull() as DeviceIdentityUnavailableException

        assertEquals(DeviceIdentityFailureMode.REPAIR_REQUIRED, error.mode)
    }

    @Test
    fun `reset removes current alias`() {
        val backend = FakeDeviceKeyStoreBackend().apply { seed(CURRENT_ALIAS) }
        val identity = AndroidKeystoreDeviceIdentity(backend, CURRENT_ALIAS)

        identity.reset()

        assertFalse(identity.hasStoredKey())
        assertEquals(listOf(CURRENT_ALIAS), backend.deletedAliases)
    }

    @Test
    fun `reset failure requires repair`() {
        val failure = IllegalStateException("delete failed")
        val backend = FakeDeviceKeyStoreBackend().apply {
            seed(CURRENT_ALIAS)
            deleteFailure = failure
        }
        val identity = AndroidKeystoreDeviceIdentity(backend, CURRENT_ALIAS)

        val error = runCatching { identity.reset() }
            .exceptionOrNull() as DeviceIdentityUnavailableException

        assertEquals(DeviceIdentityFailureMode.REPAIR_REQUIRED, error.mode)
        assertSame(failure, error.cause)
    }

    private class FakeDeviceKeyStoreBackend : DeviceKeyStoreBackend {
        private val keys = LinkedHashMap<String, KeyPair>()
        val generatedAliases = mutableListOf<String>()
        val deletedAliases = mutableListOf<String>()
        var signFailure: Throwable? = null
        var deleteFailure: Throwable? = null
        var returnMissingPrivateEntry = false
        var returnMissingCertificate = false

        override fun containsAlias(alias: String): Boolean = keys.containsKey(alias)

        override fun publicKeyEncoded(alias: String): ByteArray? {
            if (returnMissingCertificate) return null
            return keys[alias]?.public?.encoded
        }

        override fun sign(alias: String, payload: ByteArray): ByteArray? {
            signFailure?.let { throw it }
            if (returnMissingPrivateEntry) return null
            val pair = keys[alias] ?: return null
            return Signature.getInstance("SHA256withECDSA").run {
                initSign(pair.private)
                update(payload)
                sign()
            }
        }

        override fun generateEcSigningKey(alias: String) {
            generatedAliases += alias
            keys[alias] = newKeyPair()
        }

        override fun deleteEntry(alias: String) {
            deleteFailure?.let { throw it }
            deletedAliases += alias
            keys.remove(alias)
        }

        fun seed(alias: String) {
            keys[alias] = newKeyPair()
        }

        fun keyPair(alias: String): KeyPair = checkNotNull(keys[alias])

        private fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
    }

    private companion object {
        const val LEGACY_ALIAS = "hermes_bridge_device_identity_v0"
        const val CURRENT_ALIAS = "hermes_bridge_device_identity_v1"
    }
}
