package io.github.arttvad9r.typeddroid.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbShellV2Test {
    @Test
    fun `codec writes one byte channel and little endian length`() {
        val encoded = AdbShellV2Codec.encode(
            AdbShellV2Frame(AdbShellV2Channel.STDOUT, "abc".encodeToByteArray())
        )
        assertArrayEquals(byteArrayOf(1, 3, 0, 0, 0, 97, 98, 99), encoded)
    }

    @Test
    fun `decoder handles fragmentation and multiple frames`() {
        val stdout = AdbShellV2Codec.encode(
            AdbShellV2Frame(AdbShellV2Channel.STDOUT, "hello".encodeToByteArray())
        )
        val stderr = AdbShellV2Codec.encode(
            AdbShellV2Frame(AdbShellV2Channel.STDERR, "warn".encodeToByteArray())
        )
        val all = stdout + stderr
        val decoder = AdbShellV2Decoder()

        assertTrue(decoder.push(all.copyOfRange(0, 3)).isEmpty())
        val frames = decoder.push(all.copyOfRange(3, all.size))

        assertEquals(2, frames.size)
        assertEquals(AdbShellV2Channel.STDOUT, frames[0].channel)
        assertEquals("hello", frames[0].payload.decodeToString())
        assertEquals(AdbShellV2Channel.STDERR, frames[1].channel)
        assertEquals("warn", frames[1].payload.decodeToString())
        assertFalse(decoder.hasPendingBytes())
    }

    @Test
    fun `exit frame exposes unsigned exit code`() {
        assertEquals(
            255,
            shellExitCode(AdbShellV2Frame(AdbShellV2Channel.EXIT, byteArrayOf(0xff.toByte()))),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `exit frame rejects malformed payload`() {
        shellExitCode(AdbShellV2Frame(AdbShellV2Channel.EXIT, byteArrayOf(0, 1)))
    }

    @Test
    fun `shell service preserves trusted shell syntax and rejects only protocol invalid nul`() {
        assertEquals("shell,v2,raw:echo hi | grep h", buildShellV2Service("echo hi | grep h"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `shell service rejects nul`() {
        buildShellV2Service("id\u0000whoami")
    }
}
