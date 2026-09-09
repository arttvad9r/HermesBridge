package io.github.arttvad9r.hermesbridge.relay

import io.github.arttvad9r.hermesbridge.protocol.BridgeProtocol
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import org.junit.Assert.assertFalse
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
}
