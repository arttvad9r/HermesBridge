package io.github.arttvad9r.typeddroid.adb

import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbPairingClientTest {
    @Test
    fun `full in-memory pairing exchanges rsa key and returns device guid`() {
        val exporter = ByteArray(64) { (0xa0 + it).toByte() }
        val code = "123456"
        val transport = ScriptedServerTransport(
            pairingCode = code,
            exporter = exporter,
            deviceGuid = "adb-TEST_DEVICE._adb-tls-connect._tcp",
        )
        val client = AdbPairingClient { password ->
            AdbSpake2(
                role = AdbSpake2.Role.CLIENT,
                password = password,
                randomBytes = deterministicRandom(0x11),
            )
        }

        val result = client.pair(
            pairingCode = code,
            transport = transport,
            adbPublicKey = "AAAA-test-key typeddroid@android",
        )

        assertEquals("adb-TEST_DEVICE._adb-tls-connect._tcp", result.deviceGuid)
        assertEquals("AAAA-test-key typeddroid@android", transport.receivedClientPublicKey)
        assertEquals(
            listOf(AdbPairingPacketType.SPAKE2_MSG, AdbPairingPacketType.PEER_INFO),
            transport.receivedTypes,
        )
    }

    @Test
    fun `pairing rejects malformed code before sending data`() {
        val transport = ScriptedServerTransport(
            pairingCode = "123456",
            exporter = ByteArray(64),
            deviceGuid = "adb-test",
        )
        val client = AdbPairingClient()

        assertThrows(IllegalArgumentException::class.java) {
            client.pair("12345x", transport, "key comment")
        }
        assertTrue(transport.receivedTypes.isEmpty())
    }

    private class ScriptedServerTransport(
        private val pairingCode: String,
        exporter: ByteArray,
        private val deviceGuid: String,
    ) : AdbPairingTransport {
        override val exportedKeyMaterial = exporter.copyOf()
        private val outputToClient = ArrayDeque<Byte>()
        private var cipher: AdbPairingCipher? = null
        val receivedTypes = mutableListOf<AdbPairingPacketType>()
        var receivedClientPublicKey: String? = null
            private set

        override fun writeFully(data: ByteArray) {
            val packet = AdbPairingPacketCodec.decode(data)
            receivedTypes += packet.type
            when (packet.type) {
                AdbPairingPacketType.SPAKE2_MSG -> handleSpake(packet)
                AdbPairingPacketType.PEER_INFO -> handlePeerInfo(packet)
            }
        }

        override fun readFully(size: Int): ByteArray = ByteArray(size) {
            outputToClient.removeFirst()
        }

        override fun close() = Unit

        private fun handleSpake(packet: AdbPairingPacket) {
            check(cipher == null)
            val password = pairingCode.encodeToByteArray() + exportedKeyMaterial
            val server = try {
                AdbSpake2(
                    role = AdbSpake2.Role.SERVER,
                    password = password,
                    randomBytes = deterministicRandom(0x77),
                )
            } finally {
                password.fill(0)
            }
            val key = server.deriveKey(packet.payload)
            cipher = try {
                AdbPairingCipher(key)
            } finally {
                key.fill(0)
            }
            enqueue(
                AdbPairingPacketCodec.encode(
                    AdbPairingPacket(
                        type = AdbPairingPacketType.SPAKE2_MSG,
                        payload = server.message,
                    )
                )
            )
        }

        private fun handlePeerInfo(packet: AdbPairingPacket) {
            val currentCipher = checkNotNull(cipher)
            val peerInfo = currentCipher.decrypt(packet.payload)
            try {
                check(peerInfo.size == AdbPairingPeerInfo.SIZE)
                check(peerInfo[0].toInt() and 0xff == AdbPairingPeerInfo.TYPE_ADB_RSA_PUBLIC_KEY)
                val data = peerInfo.copyOfRange(1, peerInfo.size)
                val terminator = data.indexOf(0.toByte())
                check(terminator > 0)
                receivedClientPublicKey = data.copyOfRange(0, terminator).decodeToString()
            } finally {
                peerInfo.fill(0)
            }

            val serverPeerInfo = AdbPairingPeerInfo.deviceGuid(deviceGuid)
            val encrypted = try {
                currentCipher.encrypt(serverPeerInfo)
            } finally {
                serverPeerInfo.fill(0)
            }
            enqueue(
                AdbPairingPacketCodec.encode(
                    AdbPairingPacket(
                        type = AdbPairingPacketType.PEER_INFO,
                        payload = encrypted,
                    )
                )
            )
        }

        private fun enqueue(data: ByteArray) {
            data.forEach(outputToClient::addLast)
        }
    }

    companion object {
        private fun deterministicRandom(seed: Int): (Int) -> ByteArray = { size ->
            ByteArray(size) { index -> ((seed + index * 37) and 0xff).toByte() }
        }
    }
}
