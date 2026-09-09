package io.github.arttvad9r.hermesbridge.relay

import java.io.ByteArrayInputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkArtifactStoreTest {
    @Test
    fun stageComputesDigestAndRequiresExactDownloadToken() {
        val directory = Files.createTempDirectory("hermes-bridge-apk-store")
        val store = ApkArtifactStore(directory)
        val bytes = "hello".toByteArray()

        val staged = store.stage(
            fileName = "example.apk",
            input = ByteArrayInputStream(bytes),
            declaredLength = bytes.size.toLong(),
        )

        assertEquals(bytes.size.toLong(), staged.sizeBytes)
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            staged.sha256,
        )
        assertNotNull(store.findAuthorized(staged.artifactId, staged.downloadToken))
        assertNull(store.findAuthorized(staged.artifactId, "wrong-token"))
    }

    @Test
    fun expiredArtifactIsDeniedAndDeleted() {
        var now = 10_000L
        val directory = Files.createTempDirectory("hermes-bridge-apk-expiry")
        val store = ApkArtifactStore(directory, nowMillis = { now })
        val staged = store.stage(
            fileName = "example.apk",
            input = ByteArrayInputStream(byteArrayOf(1, 2, 3)),
            declaredLength = 3,
            ttlMillis = 1_000L,
        )
        val artifact = store.findAuthorized(staged.artifactId, staged.downloadToken)
        assertNotNull(artifact)
        val path = requireNotNull(artifact).path
        assertTrue(Files.exists(path))

        now += 1_001L

        assertNull(store.findAuthorized(staged.artifactId, staged.downloadToken))
        assertFalse(Files.exists(path))
    }

    @Test
    fun pathLikeOrNonApkNamesAreRejected() {
        val store = ApkArtifactStore(Files.createTempDirectory("hermes-bridge-apk-names"))
        for (name in listOf("../evil.apk", "dir/app.apk", "dir\\app.apk", "app.zip")) {
            assertThrows(InvalidApkArtifactException::class.java) {
                store.stage(name, ByteArrayInputStream(byteArrayOf(1)), 1)
            }
        }
    }

    @Test
    fun emptyAndDeclaredLengthMismatchAreRejected() {
        val store = ApkArtifactStore(Files.createTempDirectory("hermes-bridge-apk-invalid"))

        assertThrows(InvalidApkArtifactException::class.java) {
            store.stage("empty.apk", ByteArrayInputStream(byteArrayOf()), null)
        }
        assertThrows(InvalidApkArtifactException::class.java) {
            store.stage("short.apk", ByteArrayInputStream(byteArrayOf(1, 2)), 3)
        }
    }

    @Test
    fun oversizedDeclaredLengthIsRejectedBeforeReadingBody() {
        val store = ApkArtifactStore(Files.createTempDirectory("hermes-bridge-apk-size"))

        assertThrows(ApkArtifactTooLargeException::class.java) {
            store.stage(
                fileName = "large.apk",
                input = ByteArrayInputStream(byteArrayOf()),
                declaredLength = ApkArtifactStore.MAX_APK_BYTES + 1,
            )
        }
    }
}
