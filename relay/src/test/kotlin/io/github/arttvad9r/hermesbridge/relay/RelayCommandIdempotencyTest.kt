package io.github.arttvad9r.hermesbridge.relay

import io.github.arttvad9r.hermesbridge.protocol.AuthChallengePayload
import io.github.arttvad9r.hermesbridge.protocol.AuthResponsePayload
import io.github.arttvad9r.hermesbridge.protocol.BridgeProtocol
import io.github.arttvad9r.hermesbridge.protocol.CommandRequestPayload
import io.github.arttvad9r.hermesbridge.protocol.CommandResultPayload
import io.github.arttvad9r.hermesbridge.protocol.MessageType
import io.github.arttvad9r.hermesbridge.protocol.PairOkPayload
import io.github.arttvad9r.hermesbridge.protocol.PairRequestPayload
import io.github.arttvad9r.hermesbridge.protocol.WireEnvelope
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RelayCommandIdempotencyTest {
    @Test
    fun `idempotency key coalesces exact concurrent retries and rejects conflicting payload`() =
        testApplication {
            val directory = Files.createTempDirectory("hermes-bridge-idempotency-e2e")
            application {
                relayModule(
                    adminToken = ADMIN_TOKEN,
                    deviceRegistryPath = directory.resolve("devices.json"),
                    artifactDirectory = directory.resolve("artifacts"),
                )
            }

            val pairingResponse = client.post("/api/v1/pairing-codes") {
                header(HttpHeaders.Authorization, "Bearer $ADMIN_TOKEN")
            }
            val pairing = BridgeProtocol.json.decodeFromString<PairingCodeResponse>(
                pairingResponse.bodyAsText()
            )
            val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
            val publicKey = Base64.getEncoder().encodeToString(keyPair.public.encoded)
            val wsClient = createClient { install(WebSockets) }

            wsClient.webSocket("/ws/device") {
                sendEnvelope(
                    BridgeProtocol.envelope(
                        type = MessageType.PAIR_REQUEST,
                        payload = BridgeProtocol.payload(
                            PairRequestPayload(
                                code = pairing.code,
                                publicKey = publicKey,
                                appVersion = "idempotency-test",
                                deviceLabel = "CI idempotency device",
                            )
                        ),
                    )
                )
                val pairOkEnvelope = receiveEnvelope()
                val pairOk = BridgeProtocol.decodePayload<PairOkPayload>(pairOkEnvelope)
                val challengeEnvelope = receiveEnvelope()
                val challenge = BridgeProtocol.decodePayload<AuthChallengePayload>(challengeEnvelope)
                val signature = Signature.getInstance("SHA256withECDSA").run {
                    initSign(keyPair.private)
                    update(BridgeProtocol.authSigningBytes(pairOk.deviceId, challenge.challenge))
                    Base64.getEncoder().encodeToString(sign())
                }
                sendEnvelope(
                    BridgeProtocol.envelope(
                        type = MessageType.AUTH_RESPONSE,
                        deviceId = pairOk.deviceId,
                        payload = BridgeProtocol.payload(AuthResponsePayload(signature)),
                    )
                )
                assertEquals(MessageType.AUTH_OK, receiveEnvelope().type)

                val arguments = buildJsonObject { put("packageName", "com.example.app") }
                val exactBody = BridgeProtocol.json.encodeToString(
                    RelayCommandRequest(
                        tool = "apps.forceStop",
                        arguments = arguments,
                        idempotencyKey = IDEMPOTENCY_KEY,
                    )
                )

                coroutineScope {
                    val first = async {
                        client.post("/api/v1/devices/${pairOk.deviceId}/commands") {
                            header(HttpHeaders.Authorization, "Bearer $ADMIN_TOKEN")
                            contentType(ContentType.Application.Json)
                            setBody(exactBody)
                        }
                    }

                    val requestEnvelope = receiveEnvelope()
                    assertEquals(MessageType.COMMAND_REQUEST, requestEnvelope.type)
                    val request = BridgeProtocol.decodePayload<CommandRequestPayload>(requestEnvelope)
                    assertEquals(IDEMPOTENCY_KEY, request.requestId)
                    assertEquals("apps.forceStop", request.tool)
                    assertEquals(arguments, request.arguments)

                    val duplicate = async {
                        client.post("/api/v1/devices/${pairOk.deviceId}/commands") {
                            header(HttpHeaders.Authorization, "Bearer $ADMIN_TOKEN")
                            contentType(ContentType.Application.Json)
                            setBody(exactBody)
                        }
                    }
                    val conflicting = async {
                        client.post("/api/v1/devices/${pairOk.deviceId}/commands") {
                            header(HttpHeaders.Authorization, "Bearer $ADMIN_TOKEN")
                            contentType(ContentType.Application.Json)
                            setBody(
                                BridgeProtocol.json.encodeToString(
                                    RelayCommandRequest(
                                        tool = "apps.forceStop",
                                        arguments = buildJsonObject {
                                            put("packageName", "com.example.other")
                                        },
                                        idempotencyKey = IDEMPOTENCY_KEY,
                                    )
                                )
                            )
                        }
                    }

                    val conflictResponse = conflicting.await()
                    assertEquals(HttpStatusCode.OK, conflictResponse.status)
                    val conflict = BridgeProtocol.json.decodeFromString<CommandResultPayload>(
                        conflictResponse.bodyAsText()
                    )
                    assertEquals(IDEMPOTENCY_KEY, conflict.requestId)
                    assertEquals("request_id_conflict", conflict.error?.code)

                    val unexpectedFrame = withTimeoutOrNull(250L) { incoming.receive() }
                    assertNull("Concurrent retry must not send a second WebSocket command.", unexpectedFrame)

                    sendEnvelope(
                        BridgeProtocol.envelope(
                            type = MessageType.COMMAND_RESULT,
                            deviceId = pairOk.deviceId,
                            payload = BridgeProtocol.payload(
                                CommandResultPayload(
                                    requestId = IDEMPOTENCY_KEY,
                                    ok = true,
                                )
                            ),
                        )
                    )

                    val firstResult = BridgeProtocol.json.decodeFromString<CommandResultPayload>(
                        first.await().bodyAsText()
                    )
                    val duplicateResult = BridgeProtocol.json.decodeFromString<CommandResultPayload>(
                        duplicate.await().bodyAsText()
                    )
                    assertEquals(IDEMPOTENCY_KEY, firstResult.requestId)
                    assertEquals(firstResult, duplicateResult)
                }
            }
        }

    @Test
    fun `invalid idempotency key is rejected before device dispatch`() = testApplication {
        application {
            relayModule(adminToken = ADMIN_TOKEN)
        }

        val response = client.post("/api/v1/devices/device_test/commands") {
            header(HttpHeaders.Authorization, "Bearer $ADMIN_TOKEN")
            contentType(ContentType.Application.Json)
            setBody(
                BridgeProtocol.json.encodeToString(
                    RelayCommandRequest(
                        tool = "apps.forceStop",
                        idempotencyKey = "contains space",
                    )
                )
            )
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("{\"error\":\"invalid_idempotency_key\"}", response.bodyAsText())
    }

    private suspend fun DefaultClientWebSocketSession.sendEnvelope(envelope: WireEnvelope) {
        send(Frame.Text(BridgeProtocol.encode(envelope)))
    }

    private suspend fun DefaultClientWebSocketSession.receiveEnvelope(): WireEnvelope {
        val frame = incoming.receive()
        require(frame is Frame.Text) { "Expected a text protocol frame." }
        return BridgeProtocol.decode(frame.readText())
    }

    private companion object {
        const val ADMIN_TOKEN = "test-admin-token-at-least-24-chars"
        const val IDEMPOTENCY_KEY = "force-stop:123e4567-e89b-12d3-a456-426614174000"
    }
}
