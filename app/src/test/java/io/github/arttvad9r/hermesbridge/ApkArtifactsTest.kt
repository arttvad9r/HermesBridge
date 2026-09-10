package io.github.arttvad9r.hermesbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkArtifactsTest {
    @Test
    fun websocketRelayMapsOnlyToSameOriginHttpsArtifactEndpoint() {
        assertEquals(
            "https://bridge.example.com/device-artifacts",
            RelayApkArtifactRepository.artifactBaseUrlFromWebSocket(
                "wss://bridge.example.com/ws/device"
            ),
        )
        assertEquals(
            "https://bridge.example.com:8443/device-artifacts",
            RelayApkArtifactRepository.artifactBaseUrlFromWebSocket(
                "wss://bridge.example.com:8443/ws/device"
            ),
        )
    }

    @Test
    fun artifactEndpointRejectsUnsafeRelayUrls() {
        for (url in listOf(
            "ws://bridge.example.com/ws/device",
            "wss://bridge.example.com/other",
            "wss://user@bridge.example.com/ws/device",
            "wss://bridge.example.com/ws/device?next=https://evil.example",
            "wss://bridge.example.com/ws/device#fragment",
        )) {
            try {
                RelayApkArtifactRepository.artifactBaseUrlFromWebSocket(url)
                throw AssertionError("Expected relay URL rejection for $url")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun descriptorValidationAcceptsOnlyBoundedTypedMetadata() {
        RelayApkArtifactRepository.validateDescriptor(validDescriptor())

        for (descriptor in listOf(
            validDescriptor().copy(artifactId = "../apk"),
            validDescriptor().copy(downloadToken = "short"),
            validDescriptor().copy(fileName = "../app.apk"),
            validDescriptor().copy(fileName = "app.zip"),
            validDescriptor().copy(sizeBytes = 0),
            validDescriptor().copy(sizeBytes = MAX_APK_ARTIFACT_BYTES + 1),
            validDescriptor().copy(sha256 = "xyz"),
        )) {
            try {
                RelayApkArtifactRepository.validateDescriptor(descriptor)
                throw AssertionError("Expected descriptor rejection: $descriptor")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun apkDisplayVersionNameUsesSharedAppMetadataBounds() {
        assertNull(boundedApkDisplayVersionName(null))
        assertEquals("2.0", boundedApkDisplayVersionName("2.0"))

        val bounded = boundedApkDisplayVersionName(
            "release\n" + "\u0800".repeat(MAX_REMOTE_APP_VERSION_NAME_CHARS * 2)
        ) ?: throw AssertionError("Expected bounded version name")

        assertEquals(MAX_REMOTE_APP_VERSION_NAME_CHARS, bounded.length)
        assertFalse(bounded.any(Char::isISOControl))
        assertTrue(bounded.startsWith("release "))
    }

    private fun validDescriptor() = ApkArtifactDescriptor(
        artifactId = "apk_123e4567-e89b-12d3-a456-426614174000",
        downloadToken = "A".repeat(43),
        fileName = "example.apk",
        sizeBytes = 1024,
        sha256 = "a".repeat(64),
    )
}
