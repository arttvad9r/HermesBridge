package io.github.arttvad9r.hermesbridge

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
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
        assertTrue(
            isShizukuApkStagingFileName(
                "hermes-bridge-12345678-1234-1234-1234-1234567890ab.apk"
            )
        )
        assertFalse(isShizukuApkStagingPath("/data/local/tmp/other.apk"))
        assertFalse(isShizukuApkStagingPath("$path;id"))
        assertFalse(isShizukuApkStagingFileName("../hermes-bridge-12345678-1234-1234-1234-1234567890ab.apk"))
    }

    @Test
    fun crashCleanupRemovesOnlyOwnedRegularFiles() {
        val root = Files.createTempDirectory("hermes-bridge-staging-test").toFile()
        try {
            val ownedOne = root.resolve(
                "hermes-bridge-12345678-1234-1234-1234-1234567890ab.apk"
            ).apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val ownedTwo = root.resolve(
                "hermes-bridge-aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee.apk"
            ).apply { writeBytes(byteArrayOf(4, 5, 6)) }
            val unrelated = root.resolve("other.apk").apply { writeBytes(byteArrayOf(7)) }
            val lookalike = root.resolve(
                "hermes-bridge-12345678-1234-1234-1234-1234567890ab.apk.backup"
            ).apply { writeBytes(byteArrayOf(8)) }
            val matchingDirectory = root.resolve(
                "hermes-bridge-11111111-2222-3333-4444-555555555555.apk"
            ).apply { mkdir() }

            assertEquals(2, cleanupOrphanedShizukuApkStagingFiles(root))
            assertFalse(ownedOne.exists())
            assertFalse(ownedTwo.exists())
            assertTrue(unrelated.isFile)
            assertTrue(lookalike.isFile)
            assertTrue(matchingDirectory.isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cleanupIsSafeWhenDirectoryDoesNotExist() {
        val root = Files.createTempDirectory("hermes-bridge-staging-missing").toFile()
        assertTrue(root.delete())

        assertEquals(0, cleanupOrphanedShizukuApkStagingFiles(root))
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
