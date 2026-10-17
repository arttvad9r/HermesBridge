package io.github.arttvad9r.typeddroid.relay

import java.io.ByteArrayInputStream
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FileTransferStoreTest {
    @Test
    fun `download artifact is token gated and hashed`() {
        val root = Files.createTempDirectory("typed-droid-transfer-download")
        val store = FileTransferStore(root)
        val bytes = "hello transfer".toByteArray()
        val staged = store.stageDownload("hello.txt", ByteArrayInputStream(bytes), bytes.size.toLong())
        assertEquals(bytes.size.toLong(), staged.sizeBytes)
        assertEquals(64, staged.sha256.length)
        assertNull(store.findDownloadAuthorized(staged.artifactId, "wrong"))
        val artifact = store.findDownloadAuthorized(staged.artifactId, staged.downloadToken)
        assertNotNull(artifact)
        assertArrayEquals(bytes, Files.readAllBytes(requireNotNull(artifact).path))
    }

    @Test
    fun `upload slot is one use and visible to admin`() {
        val root = Files.createTempDirectory("typed-droid-transfer-upload")
        val store = FileTransferStore(root)
        val slot = store.createUploadSlot()
        assertFalse(requireNotNull(store.uploadStatus(slot.uploadId)).complete)
        val bytes = ByteArray(4096) { (it % 251).toByte() }
        val complete = store.receiveUpload(
            slot.uploadId,
            slot.uploadToken,
            "phone.bin",
            ByteArrayInputStream(bytes),
            bytes.size.toLong(),
        )
        assertTrue(complete.complete)
        assertEquals("phone.bin", complete.fileName)
        val uploaded = requireNotNull(store.completedUpload(slot.uploadId))
        assertArrayEquals(bytes, Files.readAllBytes(uploaded.path))
        assertThrows(FileTransferConflictException::class.java) {
            store.receiveUpload(slot.uploadId, slot.uploadToken, "again.bin", ByteArrayInputStream(bytes))
        }
    }

    @Test
    fun `invalid file names and oversized declarations fail closed`() {
        val store = FileTransferStore(Files.createTempDirectory("typed-droid-transfer-invalid"))
        assertThrows(InvalidFileTransferException::class.java) {
            store.stageDownload("../secret", ByteArrayInputStream(byteArrayOf(1)))
        }
        assertThrows(FileTransferTooLargeException::class.java) {
            store.stageDownload("x.bin", ByteArrayInputStream(byteArrayOf(1)), FileTransferStore.MAX_FILE_BYTES + 1)
        }
    }

    @Test
    fun `expired slots and artifacts disappear`() {
        var now = 1000L
        val store = FileTransferStore(
            Files.createTempDirectory("typed-droid-transfer-expiry"),
            nowMillis = { now },
        )
        val staged = store.stageDownload("x.bin", ByteArrayInputStream(byteArrayOf(1)), ttlMillis = 1000)
        val slot = store.createUploadSlot(ttlMillis = 1000)
        now += 1001
        store.cleanupExpired()
        assertNull(store.findDownloadAuthorized(staged.artifactId, staged.downloadToken))
        assertNull(store.uploadStatus(slot.uploadId))
    }
}
