package io.github.arttvad9r.typeddroid.adb

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbIdentityTest {
    @Test
    fun `android adb public key encoding has expected fields`() {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        val key = generator.generateKeyPair().public as RSAPublicKey

        val encoded = AdbRsaPublicKeyEncoder.encode(key)
        assertEquals(AdbRsaPublicKeyEncoder.ENCODED_BYTES, encoded.size)

        val buffer = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(64, buffer.int)
        val n0Inv = buffer.int.toLong() and 0xffff_ffffL
        val modulusLe = ByteArray(256).also(buffer::get)
        val rrLe = ByteArray(256).also(buffer::get)
        assertEquals(key.publicExponent.toInt(), buffer.int)

        val modulus = littleEndianUnsigned(modulusLe)
        assertEquals(key.modulus, modulus)
        val r32 = BigInteger.ONE.shiftLeft(32)
        val equation = key.modulus.mod(r32)
            .multiply(BigInteger.valueOf(n0Inv))
            .add(BigInteger.ONE)
            .mod(r32)
        assertEquals(BigInteger.ZERO, equation)

        val expectedRr = BigInteger.ONE.shiftLeft(2048).modPow(BigInteger.TWO, key.modulus)
        assertEquals(expectedRr, littleEndianUnsigned(rrLe))
    }

    @Test
    fun `public key string contains fixed payload and comment`() {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        val key = generator.generateKeyPair().public as RSAPublicKey

        val value = AdbRsaPublicKeyEncoder.publicKeyString(key, "typed@test")
        val parts = value.split(' ')
        assertEquals(2, parts.size)
        assertEquals("typed@test", parts[1])
        assertEquals(
            AdbRsaPublicKeyEncoder.ENCODED_BYTES,
            java.util.Base64.getDecoder().decode(parts[0]).size,
        )
        assertTrue(value.length < AdbPairingPeerInfo.SIZE)
    }

    private fun littleEndianUnsigned(bytes: ByteArray): BigInteger {
        val reversed = bytes.reversedArray()
        return BigInteger(1, reversed)
    }
}
