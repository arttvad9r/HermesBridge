package io.github.arttvad9r.typeddroid.relay

import io.github.arttvad9r.typeddroid.protocol.BridgeProtocol
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileTransferRouteTest {
    @Test
    fun `admin staged artifact downloads only with one-time bearer token`() = testApplication {
        val directory = Files.createTempDirectory("typed-droid-file-route")
        application { relayModule(adminToken = ADMIN_TOKEN, transferDirectory = directory.resolve("transfers")) }
        val bytes = "host to phone transfer".toByteArray()
        val stage = client.post("/api/v1/file-artifacts") {
            header(HttpHeaders.Authorization, "Bearer $ADMIN_TOKEN")
            header(FILE_NAME_HEADER, "hello.txt")
            contentType(ContentType.Application.OctetStream)
            setBody(bytes)
        }
        assertEquals(HttpStatusCode.Created, stage.status)
        val artifact = BridgeProtocol.json.decodeFromString<StagedFileResponse>(stage.bodyAsText())

        assertEquals(HttpStatusCode.NotFound, client.get("/file-artifacts/${artifact.artifactId}").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/file-artifacts/${artifact.artifactId}") {
            header(HttpHeaders.Authorization, "Bearer wrong")
        }.status)
        val download = client.get("/file-artifacts/${artifact.artifactId}") {
            header(HttpHeaders.Authorization, "Bearer ${artifact.downloadToken}")
        }
        assertEquals(HttpStatusCode.OK, download.status)
        assertEquals(String(bytes), download.bodyAsText())
    }

    @Test
    fun `device upload slot is bearer gated one-use and admin retrievable`() = testApplication {
        val directory = Files.createTempDirectory("typed-droid-upload-route")
        application { relayModule(adminToken = ADMIN_TOKEN, transferDirectory = directory.resolve("transfers")) }
        val slotResponse = client.post("/api/v1/file-upload-slots") {
            header(HttpHeaders.Authorization, "Bearer $ADMIN_TOKEN")
        }
        assertEquals(HttpStatusCode.Created, slotResponse.status)
        val slot = BridgeProtocol.json.decodeFromString<FileUploadSlotResponse>(slotResponse.bodyAsText())
        val bytes = "phone to host transfer".toByteArray()

        val denied = client.post("/device-file-uploads/${slot.uploadId}") {
            header(HttpHeaders.Authorization, "Bearer wrong")
            header(FILE_NAME_HEADER, "phone.txt")
            contentType(ContentType.Application.OctetStream)
            setBody(bytes)
        }
        assertEquals(HttpStatusCode.NotFound, denied.status)

        val upload = client.post("/device-file-uploads/${slot.uploadId}") {
            header(HttpHeaders.Authorization, "Bearer ${slot.uploadToken}")
            header(FILE_NAME_HEADER, "phone.txt")
            contentType(ContentType.Application.OctetStream)
            setBody(bytes)
        }
        assertEquals(HttpStatusCode.Created, upload.status)
        val completed = BridgeProtocol.json.decodeFromString<FileUploadStatusResponse>(upload.bodyAsText())
        assertTrue(completed.complete)
        assertEquals(bytes.size.toLong(), completed.sizeBytes)

        val repeated = client.post("/device-file-uploads/${slot.uploadId}") {
            header(HttpHeaders.Authorization, "Bearer ${slot.uploadToken}")
            header(FILE_NAME_HEADER, "phone.txt")
            setBody(bytes)
        }
        assertEquals(HttpStatusCode.Conflict, repeated.status)

        val status = client.get("/api/v1/file-upload-slots/${slot.uploadId}") {
            header(HttpHeaders.Authorization, "Bearer $ADMIN_TOKEN")
        }
        assertEquals(HttpStatusCode.OK, status.status)
        val statusBody = BridgeProtocol.json.decodeFromString<FileUploadStatusResponse>(status.bodyAsText())
        assertTrue(statusBody.complete)
        assertEquals("phone.txt", statusBody.fileName)

        val unauthorizedContent = client.get("/api/v1/file-upload-slots/${slot.uploadId}/content")
        assertEquals(HttpStatusCode.Unauthorized, unauthorizedContent.status)
        val content = client.get("/api/v1/file-upload-slots/${slot.uploadId}/content") {
            header(HttpHeaders.Authorization, "Bearer $ADMIN_TOKEN")
        }
        assertEquals(HttpStatusCode.OK, content.status)
        assertEquals(String(bytes), content.bodyAsText())
    }

    @Test
    fun `arbitrary file names are rejected`() = testApplication {
        application { relayModule(adminToken = ADMIN_TOKEN) }
        val badName = client.post("/api/v1/file-artifacts") {
            header(HttpHeaders.Authorization, "Bearer $ADMIN_TOKEN")
            header(FILE_NAME_HEADER, "../secret")
            setBody(byteArrayOf(1))
        }
        assertEquals(HttpStatusCode.BadRequest, badName.status)
    }

    private companion object {
        const val ADMIN_TOKEN = "test-admin-token-at-least-24-chars"
        const val FILE_NAME_HEADER = "X-TypedDroid-File-Name"
    }
}
