package io.github.arttvad9r.typeddroid.adb

import javax.crypto.AEADBadTagException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AdbPairingCipherTest {
    @Test
    fun `cipher directions use independent sequence counters`() {
        val material = ByteArray(64) { it.toByte() }
        val client = AdbPairingCipher(material)
        val server = AdbPairingCipher(material)

        val clientMessage = client.encrypt("client".encodeToByteArray())
        assertEquals(6 + 16, clientMessage.size)
        assertArrayEquals("client".encodeToByteArray(), server.decrypt(clientMessage))

        val serverMessage = server.encrypt("server".encodeToByteArray())
        assertArrayEquals("server".encodeToByteArray(), client.decrypt(serverMessage))

        val second = client.encrypt("two".encodeToByteArray())
        assertArrayEquals("two".encodeToByteArray(), server.decrypt(second))
    }

    @Test
    fun `tampered ciphertext fails authentication without advancing receive sequence`() {
        val material = ByteArray(64) { (it * 7).toByte() }
        val sender = AdbPairingCipher(material)
        val receiver = AdbPairingCipher(material)
        val ciphertext = sender.encrypt("secret".encodeToByteArray())
        val tampered = ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }

        assertThrows(AEADBadTagException::class.java) { receiver.decrypt(tampered) }
        assertArrayEquals("secret".encodeToByteArray(), receiver.decrypt(ciphertext))
    }

    @Test
    fun `hkdf matches rfc5869 sha256 test case one`() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = hex("000102030405060708090a0b0c")
        val info = hex("f0f1f2f3f4f5f6f7f8f9")
        val expected = hex(
            "3cb25f25faacd57a90434f64d0362f2a" +
                "2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                "34007208d5b887185865"
        )

        assertArrayEquals(expected, hkdfSha256(ikm, info, 42, salt))
    }

    private fun hex(value: String): ByteArray = ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
