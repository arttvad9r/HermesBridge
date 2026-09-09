package io.github.arttvad9r.hermesbridge.relay

import io.github.arttvad9r.hermesbridge.protocol.BridgeProtocol
import io.github.arttvad9r.hermesbridge.protocol.CommandResultPayload
import io.github.arttvad9r.hermesbridge.protocol.ProtocolError
import io.ktor.http.HttpStatusCode
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayStateTest {
    @Test
    fun pairingCodeIsOneTimeAndExpires() {
        var now = 1_000L
        val store = PairingCodeStore(nowMillis = { now })
        val first = store.create(ttlMillis = 100L)
        assertTrue(store.consume(first.code))
        assertFalse(store.consume(first.code))

        val expired = store.create(ttlMillis = 100L)
        now += 101L
        assertFalse(store.consume(expired.code))
    }

    @Test
    fun registeredDeviceSignatureCanBeVerified() {
        val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val registry = DeviceRegistry()
        val device = registry.register(keyPair.public, "test")
        val challenge = "test-challenge"
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(BridgeProtocol.authSigningBytes(device.deviceId, challenge))
            Base64.getEncoder().encodeToString(sign())
        }

        assertTrue(AuthCrypto.verify(device, challenge, signature))
        assertFalse(AuthCrypto.verify(device, "other-challenge", signature))
    }

    @Test
    fun registeredDeviceSurvivesRegistryRestart() {
        val directory = Files.createTempDirectory("hermes-bridge-relay-test")
        val store = directory.resolve("devices.json")
        val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()

        val firstRegistry = DeviceRegistry(store)
        val registered = firstRegistry.register(keyPair.public, "My phone")

        val restoredRegistry = DeviceRegistry(store)
        val restored = requireNotNull(restoredRegistry.find(registered.deviceId))

        assertEquals("My phone", restored.label)
        assertTrue(keyPair.public.encoded.contentEquals(restored.publicKey.encoded))
    }

    @Test
    fun revokedDeviceIsRemovedPersistently() {
        val directory = Files.createTempDirectory("hermes-bridge-relay-revoke")
        val store = directory.resolve("devices.json")
        val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()

        val registry = DeviceRegistry(store)
        val registered = registry.register(keyPair.public, "Revoked phone")
        assertTrue(registry.revoke(registered.deviceId))
        assertNull(registry.find(registered.deviceId))
        assertFalse(registry.revoke(registered.deviceId))

        val restoredRegistry = DeviceRegistry(store)
        assertNull(restoredRegistry.find(registered.deviceId))
        assertTrue(restoredRegistry.list().isEmpty())
    }

    @Test
    fun commandHttpStatusPreservesDeviceLevelErrors() {
        val policyDenied = CommandResultPayload(
            requestId = "request-1",
            ok = false,
            error = ProtocolError("policy_denied", "Denied locally."),
        )
        val invalidArguments = CommandResultPayload(
            requestId = "request-2",
            ok = false,
            error = ProtocolError("invalid_arguments", "Bad arguments."),
        )

        assertEquals(HttpStatusCode.OK, commandHttpStatus(policyDenied))
        assertEquals(HttpStatusCode.OK, commandHttpStatus(invalidArguments))
    }

    @Test
    fun commandHttpStatusUsesTransportErrorsForUnavailableDevice() {
        val offline = CommandResultPayload(
            requestId = "",
            ok = false,
            error = ProtocolError("device_offline", "Offline."),
        )
        val timeout = CommandResultPayload(
            requestId = "",
            ok = false,
            error = ProtocolError("command_timeout", "Timeout."),
        )

        assertEquals(HttpStatusCode.ServiceUnavailable, commandHttpStatus(offline))
        assertEquals(HttpStatusCode.GatewayTimeout, commandHttpStatus(timeout))
    }
}
