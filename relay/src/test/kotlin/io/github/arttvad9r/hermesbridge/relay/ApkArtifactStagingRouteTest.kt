package io.github.arttvad9r.hermesbridge.relay

import io.github.arttvad9r.hermesbridge.protocol.BridgeProtocol
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Test

class ApkArtifactStagingRouteTest {
    @Test
    fun `exact keyed restage returns identical descriptor and changed bytes conflict`() =
        testApplication {
            application {
                relayModule(adminToken = ADMIN_TOKEN)
            }

            val firstBytes = "same staged apk".toByteArray()
            val firstResponse = client.post("/api/v1/apk-artifacts") {
                header(HttpHeaders.Authorization, "Bearer $ADMIN_TOKEN")
                header(APK_NAME_HEADER, "example.apk")
                header(APK_STAGING_KEY_HEADER, STAGING_KEY)
                contentType(ContentType.Application.OctetStream)
                setBody(firstBytes)
            }
            assertEquals(HttpStatusCode.Created, firstResponse.status)
            val first = BridgeProtocol.json.decodeFromString<StagedApkResponse>(
                firstResponse.bodyAsText()
            )

            val retryResponse = client.post("/api/v1/apk-artifacts") {
                header(HttpHeaders.Authorization, "Bearer $ADMIN_TOKEN")
                header(APK_NAME_HEADER, "example.apk")
                header(APK_STAGING_KEY_HEADER, STAGING_KEY)
                contentType(ContentType.Application.OctetStream)
                setBody(firstBytes)
            }
            assertEquals(HttpStatusCode.Created, retryResponse.status)
            val retry = BridgeProtocol.json.decodeFromString<StagedApkResponse>(
                retryResponse.bodyAsText()
            )
            assertEquals(first, retry)

            val conflictResponse = client.post("/api/v1/apk-artifacts") {
                header(HttpHeaders.Authorization, "Bearer $ADMIN_TOKEN")
                header(APK_NAME_HEADER, "example.apk")
                header(APK_STAGING_KEY_HEADER, STAGING_KEY)
                contentType(ContentType.Application.OctetStream)
                setBody("different staged apk".toByteArray())
            }
            assertEquals(HttpStatusCode.Conflict, conflictResponse.status)
            assertEquals(
                "{\"error\":\"apk_staging_conflict\"}",
                conflictResponse.bodyAsText(),
            )
        }

    @Test
    fun `invalid staging key is rejected before staging`() = testApplication {
        application {
            relayModule(adminToken = ADMIN_TOKEN)
        }

        val response = client.post("/api/v1/apk-artifacts") {
            header(HttpHeaders.Authorization, "Bearer $ADMIN_TOKEN")
            header(APK_NAME_HEADER, "example.apk")
            header(APK_STAGING_KEY_HEADER, "contains space")
            contentType(ContentType.Application.OctetStream)
            setBody("apk".toByteArray())
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("{\"error\":\"invalid_apk_staging_key\"}", response.bodyAsText())
    }

    private companion object {
        const val ADMIN_TOKEN = "test-admin-token-at-least-24-chars"
        const val APK_NAME_HEADER = "X-Hermes-Apk-Name"
        const val APK_STAGING_KEY_HEADER = "X-Hermes-Apk-Staging-Key"
        const val STAGING_KEY = "stage:retry-123"
    }
}
