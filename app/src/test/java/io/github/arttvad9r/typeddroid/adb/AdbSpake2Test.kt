package io.github.arttvad9r.typeddroid.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class AdbSpake2Test {
    @Test
    fun `deterministic adb pairing vector matches independent ed25519 calculation`() {
        val password = "123456".encodeToByteArray() + ByteArray(64) { it.toByte() }
        val client = AdbSpake2(
            AdbSpake2.Role.CLIENT,
            password,
            deterministicRandom(0x11),
        )
        val server = AdbSpake2(
            AdbSpake2.Role.SERVER,
            password,
            deterministicRandom(0x77),
        )

        assertArrayEquals(
            hex("edecc136637307b96c5cb02d1cc5bc04b5af12b531074b47d87dfd6389f5d351"),
            client.message,
        )
        assertArrayEquals(
            hex("45d64979a5a6e814ee2b9b68d924f20c73faad6155ef3995644a63e5a31ec72d"),
            server.message,
        )

        val clientKey = client.deriveKey(server.message)
        val serverKey = server.deriveKey(client.message)
        val expectedKey = hex(
            "8b1f5ffce64b237377b74294f5227a4982d633948893cbe5e057ed64497695d7" +
                "bd5534669622f84a0c03a8041ae3e55d676c4ec7b624fd2ba04493fefe7979eb"
        )

        assertEquals(64, clientKey.size)
        assertArrayEquals(expectedKey, clientKey)
        assertArrayEquals(expectedKey, serverKey)
    }

    @Test
    fun `wrong password derives different key`() {
        val client = AdbSpake2(
            AdbSpake2.Role.CLIENT,
            "password".encodeToByteArray(),
            deterministicRandom(1),
        )
        val server = AdbSpake2(
            AdbSpake2.Role.SERVER,
            "wrong password".encodeToByteArray(),
            deterministicRandom(2),
        )

        val clientKey = client.deriveKey(server.message)
        val serverKey = server.deriveKey(client.message)

        assertFalse(clientKey.contentEquals(serverKey))
    }

    @Test
    fun `peer message can only be processed once`() {
        val password = "password".encodeToByteArray()
        val client = AdbSpake2(AdbSpake2.Role.CLIENT, password, deterministicRandom(3))
        val server = AdbSpake2(AdbSpake2.Role.SERVER, password, deterministicRandom(4))
        client.deriveKey(server.message)
        assertThrows(IllegalStateException::class.java) { client.deriveKey(server.message) }
    }

    private fun deterministicRandom(seed: Int): (Int) -> ByteArray = { size ->
        ByteArray(size) { index -> ((seed + index * 37) and 0xff).toByte() }
    }

    private fun hex(value: String): ByteArray = ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
