package io.github.arttvad9r.hermesbridge

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegedApkInstallStagingTest {
    @Test
    fun generatedPathIsConfinedToShellStagingDirectory() {
        val path = createShizukuApkStagingPath("12345678-1234-1234-1234-1234567890ab")

        assertEquals(
            "/data/local/tmp/hermes-bridge-12345678-1234-1234-1234-1234567890ab.apk",
            path,
        )
        assertTrue(isShizukuApkStagingPath(path))
        assertFalse(isShizukuApkStagingPath("/data/local/tmp/other.apk"))
        assertFalse(isShizukuApkStagingPath("$path;id"))
    }

    @Test
    fun exactVerifiedByteCountIsCopied() {
        val bytes = ByteArray(32 * 1024) { index -> (index % 251).toByte() }
        val output = ByteArrayOutputStream()

        val copied = copyExactApkBytes(
            input = ByteArrayInputStream(bytes),
            output = output,
            expectedSize = bytes.size.toLong(),
        )

        assertEquals(bytes.size.toLong(), copied)
        assertArrayEquals(bytes, output.toByteArray())
    }

    @Test
    fun truncatedStreamIsRejected() {
        val bytes = byteArrayOf(1, 2, 3)

        assertTrue(
            runCatching {
                copyExactApkBytes(
                    input = ByteArrayInputStream(bytes),
                    output = ByteArrayOutputStream(),
                    expectedSize = 4,
                )
            }.exceptionOrNull() is ApkStagingSizeException
        )
    }

    @Test
    fun oversizedStreamIsRejectedBeforeExtraBytesAreWritten() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val output = ByteArrayOutputStream()

        assertTrue(
            runCatching {
                copyExactApkBytes(
                    input = ByteArrayInputStream(bytes),
                    output = output,
                    expectedSize = 3,
                )
            }.exceptionOrNull() is ApkStagingSizeException
        )
        assertEquals(0, output.size())
    }
}
