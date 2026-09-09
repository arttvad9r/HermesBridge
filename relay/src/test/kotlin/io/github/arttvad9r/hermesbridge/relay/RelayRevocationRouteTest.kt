package io.github.arttvad9r.hermesbridge.relay

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.security.KeyPairGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class RelayRevocationRouteTest {
    @Test
    fun revokeRequiresAdminTokenAndPersistsRemoval() = testApplication {
        val directory = Files.createTempDirectory("hermes-bridge-revoke-route")
        val store = directory.resolve("devices.json")
        val artifactDirectory = directory.resolve("artifacts")
        val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val registered = DeviceRegistry(store).register(keyPair.public, "My phone")

        application {
            relayModule(
                adminToken = ADMIN_TOKEN,
                deviceRegistryPath = store,
                artifactDirectory = artifactDirectory,
            )
        }

        val unauthorized = client.post("/api/v1/devices/${registered.deviceId}/revoke")
        assertEquals(HttpStatusCode.Unauthorized, unauthorized.status)
        assertNotNull(DeviceRegistry(store).find(registered.deviceId))

        val revoked = client.post("/api/v1/devices/${registered.deviceId}/revoke") {
            header(HttpHeaders.Authorization, "Bearer $ADMIN_TOKEN")
        }
        assertEquals(HttpStatusCode.OK, revoked.status)
        assertNull(DeviceRegistry(store).find(registered.deviceId))

        val repeated = client.post("/api/v1/devices/${registered.deviceId}/revoke") {
            header(HttpHeaders.Authorization, "Bearer $ADMIN_TOKEN")
        }
        assertEquals(HttpStatusCode.NotFound, repeated.status)
    }

    @Test
    fun revokeRejectsMalformedDeviceId() = testApplication {
        val directory = Files.createTempDirectory("hermes-bridge-revoke-invalid")
        application {
            relayModule(
                adminToken = ADMIN_TOKEN,
                deviceRegistryPath = directory.resolve("devices.json"),
                artifactDirectory = directory.resolve("artifacts"),
            )
        }

        val response = client.post("/api/v1/devices/..%2Fetc%2Fpasswd/revoke") {
            header(HttpHeaders.Authorization, "Bearer $ADMIN_TOKEN")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    private companion object {
        const val ADMIN_TOKEN = "test-admin-token-at-least-24-chars"
    }
}
