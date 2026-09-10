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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayProtocolE2ETest {
    @Test
    fun `pair authenticate and round trip a typed command over websocket`() = testApplication {
        val directory = Files.createTempDirectory("hermes-bridge-protocol-e2e")
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
        assertEquals(HttpStatusCode.OK, pairingResponse.status)
        val pairing = BridgeProtocol.json.decodeFromString<PairingCodeResponse>(
            pairingResponse.bodyAsText()
        )

        val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val publicKey = Base64.getEncoder().encodeToString(keyPair.public.encoded)
        val wsClient = createClient {
            install(WebSockets)
        }

        wsClient.webSocket("/ws/device") {
            sendEnvelope(
                BridgeProtocol.envelope(
                    type = MessageType.PAIR_REQUEST,
                    payload = BridgeProtocol.payload(
                        PairRequestPayload(
                            code = pairing.code,
                            publicKey = publicKey,
                            appVersion = "e2e-test",
                            deviceLabel = "CI fake device",
                        )
                    ),
                )
            )

            val pairOkEnvelope = receiveEnvelope()
            assertEquals(MessageType.PAIR_OK, pairOkEnvelope.type)
            val pairOk = BridgeProtocol.decodePayload<PairOkPayload>(pairOkEnvelope)
            assertEquals(pairOk.deviceId, pairOkEnvelope.deviceId)

            val challengeEnvelope = receiveEnvelope()
            assertEquals(MessageType.AUTH_CHALLENGE, challengeEnvelope.type)
            assertEquals(pairOk.deviceId, challengeEnvelope.deviceId)
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

            val authOk = receiveEnvelope()
            assertEquals(MessageType.AUTH_OK, authOk.type)
            assertEquals(pairOk.deviceId, authOk.deviceId)

            coroutineScope {
                val adminCommand = async {
                    client.post("/api/v1/devices/${pairOk.deviceId}/commands") {
                        header(HttpHeaders.Authorization, "Bearer $ADMIN_TOKEN")
                        contentType(ContentType.Application.Json)
                        setBody(
                            BridgeProtocol.json.encodeToString(
                                RelayCommandRequest(
                                    tool = "device.health",
                                )
                            )
                        )
                    }
                }

                val requestEnvelope = receiveEnvelope()
                assertEquals(MessageType.COMMAND_REQUEST, requestEnvelope.type)
                assertEquals(pairOk.deviceId, requestEnvelope.deviceId)
                val request = BridgeProtocol.decodePayload<CommandRequestPayload>(requestEnvelope)
                assertEquals("device.health", request.tool)
                assertTrue(request.requestId.isNotBlank())

                val deviceResult = buildJsonObject {
                    put("batteryPct", 73)
                    put("freeStorageBytes", 123456789L)
                }
                sendEnvelope(
                    BridgeProtocol.envelope(
                        type = MessageType.COMMAND_RESULT,
                        deviceId = pairOk.deviceId,
                        payload = BridgeProtocol.payload(
                            CommandResultPayload(
                                requestId = request.requestId,
                                ok = true,
                                result = deviceResult,
                            )
                        ),
                    )
                )

                val response = adminCommand.await()
                assertEquals(HttpStatusCode.OK, response.status)
                val result = BridgeProtocol.json.decodeFromString<CommandResultPayload>(
                    response.bodyAsText()
                )
                assertEquals(request.requestId, result.requestId)
                assertTrue(result.ok)
                assertEquals(deviceResult, result.result)
            }
        }
    }

    private suspend fun DefaultClientWebSocketSession.sendEnvelope(envelope: WireEnvelope) {
        send(Frame.Text(BridgeProtocol.encode(envelope)))
    }

    private suspend fun DefaultClientWebSocketSession.receiveEnvelope(): WireEnvelope {
        val frame = incoming.receive() as Frame.Text
        return BridgeProtocol.decode(frame.readText())
    }

    private companion object {
        const val ADMIN_TOKEN = "relay-e2e-admin-token"
    }
}
