package io.github.arttvad9r.hermesbridge.relay

import java.io.ByteArrayInputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkArtifactStoreTest {
    @Test
    fun startupCleanupRemovesOnlyStoreOwnedOrphans() {
        val directory = Files.createTempDirectory("hermes-bridge-apk-orphans")
        val orphanArtifact = directory.resolve(
            "apk_123e4567-e89b-12d3-a456-426614174000.apk"
        )
        val orphanUpload = directory.resolve(".upload-abandoned.tmp")
        val unrelatedApk = directory.resolve("apk_notes.apk")
        val sentinel = directory.resolve("keep.txt")
        val matchingDirectory = directory.resolve(
            "apk_223e4567-e89b-12d3-a456-426614174000.apk"
        )
        Files.write(orphanArtifact, byteArrayOf(1, 2, 3))
        Files.write(orphanUpload, byteArrayOf(4, 5, 6))
        Files.write(unrelatedApk, byteArrayOf(7))
        Files.write(sentinel, byteArrayOf(8))
        Files.createDirectory(matchingDirectory)

        val store = ApkArtifactStore(directory)

        assertFalse(Files.exists(orphanArtifact))
        assertFalse(Files.exists(orphanUpload))
        assertTrue(Files.exists(unrelatedApk))
        assertTrue(Files.exists(sentinel))
        assertTrue(Files.isDirectory(matchingDirectory))

        val bytes = "new apk after cleanup".toByteArray()
        val staged = store.stage(
            fileName = "example.apk",
            input = ByteArrayInputStream(bytes),
            declaredLength = bytes.size.toLong(),
        )
        assertNotNull(store.findAuthorized(staged.artifactId, staged.downloadToken))
    }

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
    fun unkeyedRestageRemainsAFreshArtifact() {
        val directory = Files.createTempDirectory("hermes-bridge-apk-fresh")
        val store = ApkArtifactStore(directory)
        val bytes = "same apk bytes".toByteArray()

        val first = store.stage(
            fileName = "example.apk",
            input = ByteArrayInputStream(bytes),
            declaredLength = bytes.size.toLong(),
        )
        val second = store.stage(
            fileName = "example.apk",
            input = ByteArrayInputStream(bytes),
            declaredLength = bytes.size.toLong(),
        )

        assertNotEquals(first.artifactId, second.artifactId)
        assertNotEquals(first.downloadToken, second.downloadToken)
    }

    @Test
    fun exactKeyedRestageReusesVerifiedDescriptor() {
        val directory = Files.createTempDirectory("hermes-bridge-apk-retry")
        val store = ApkArtifactStore(directory)
        val bytes = "same apk bytes".toByteArray()

        val first = store.stage(
            fileName = "example.apk",
            input = ByteArrayInputStream(bytes),
            declaredLength = bytes.size.toLong(),
            stagingKey = STAGING_KEY,
        )
        val retry = store.stage(
            fileName = "example.apk",
            input = ByteArrayInputStream(bytes),
            declaredLength = bytes.size.toLong(),
            stagingKey = STAGING_KEY,
        )

        assertEquals(first, retry)
        Files.list(directory).use { files ->
            assertEquals(1L, files.count())
        }
    }

    @Test
    fun sameStagingKeyRejectsChangedVerifiedContent() {
        val directory = Files.createTempDirectory("hermes-bridge-apk-conflict")
        val store = ApkArtifactStore(directory)
        val firstBytes = "first apk bytes".toByteArray()
        val changedBytes = "changed apk bytes".toByteArray()

        store.stage(
            fileName = "example.apk",
            input = ByteArrayInputStream(firstBytes),
            declaredLength = firstBytes.size.toLong(),
            stagingKey = STAGING_KEY,
        )

        assertThrows(ApkStagingConflictException::class.java) {
            store.stage(
                fileName = "example.apk",
                input = ByteArrayInputStream(changedBytes),
                declaredLength = changedBytes.size.toLong(),
                stagingKey = STAGING_KEY,
            )
        }
    }

    @Test
    fun expiredKeyedArtifactFailsClosedAndNewIdentityCanRestage() {
        var now = 10_000L
        val directory = Files.createTempDirectory("hermes-bridge-apk-expiry")
        val store = ApkArtifactStore(directory, nowMillis = { now })
        val bytes = byteArrayOf(1, 2, 3)
        val staged = store.stage(
            fileName = "example.apk",
            input = ByteArrayInputStream(bytes),
            declaredLength = bytes.size.toLong(),
            ttlMillis = 1_000L,
            stagingKey = STAGING_KEY,
        )
        val artifact = store.findAuthorized(staged.artifactId, staged.downloadToken)
        assertNotNull(artifact)
        val path = requireNotNull(artifact).path
        assertTrue(Files.exists(path))

        now += 1_001L

        assertNull(store.findAuthorized(staged.artifactId, staged.downloadToken))
        assertFalse(Files.exists(path))
        assertThrows(ApkStagingExpiredException::class.java) {
            store.stage(
                fileName = "example.apk",
                input = ByteArrayInputStream(bytes),
                declaredLength = bytes.size.toLong(),
                ttlMillis = 1_000L,
                stagingKey = STAGING_KEY,
            )
        }

        val restaged = store.stage(
            fileName = "example.apk",
            input = ByteArrayInputStream(bytes),
            declaredLength = bytes.size.toLong(),
            ttlMillis = 1_000L,
            stagingKey = OTHER_STAGING_KEY,
        )
        assertNotEquals(staged.artifactId, restaged.artifactId)
        assertNotEquals(staged.downloadToken, restaged.downloadToken)
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

    private companion object {
        const val STAGING_KEY = "stage:retry-1"
        const val OTHER_STAGING_KEY = "stage:retry-2"
    }
}
