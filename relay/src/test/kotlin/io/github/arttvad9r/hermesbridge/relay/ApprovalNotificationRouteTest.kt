package io.github.arttvad9r.hermesbridge.relay

import io.github.arttvad9r.hermesbridge.protocol.ApprovalRequestPayload
import io.github.arttvad9r.hermesbridge.protocol.AuthChallengePayload
import io.github.arttvad9r.hermesbridge.protocol.AuthResponsePayload
import io.github.arttvad9r.hermesbridge.protocol.BridgeProtocol
import io.github.arttvad9r.hermesbridge.protocol.ErrorPayload
import io.github.arttvad9r.hermesbridge.protocol.MessageType
import io.github.arttvad9r.hermesbridge.protocol.PairOkPayload
import io.github.arttvad9r.hermesbridge.protocol.PairRequestPayload
import io.github.arttvad9r.hermesbridge.protocol.WireEnvelope
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalNotificationRouteTest {
    @Test
    fun `authenticated device can publish target-free approval notification for read-only admin listing`() =
        testApplication {
            val directory = Files.createTempDirectory("hermes-bridge-approval-route")
            application {
                relayModule(
                    adminToken = ADMIN_TOKEN,
                    deviceRegistryPath = directory.resolve("devices.json"),
                    artifactDirectory = directory.resolve("artifacts"),
                )
            }

            val pairing = BridgeProtocol.json.decodeFromString<PairingCodeResponse>(
                client.post("/api/v1/pairing-codes") {
                    header(HttpHeaders.Authorization, "Bearer $ADMIN_TOKEN")
                }.bodyAsText()
            )
            val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
            val wsClient = createClient { install(WebSockets) }

            wsClient.webSocket("/ws/device") {
                val deviceId = authenticate(pairing.code, keyPair)
                val approvalId = "approval_${UUID.randomUUID()}"
                sendEnvelope(
                    BridgeProtocol.envelope(
                        type = MessageType.APPROVAL_REQUEST,
                        deviceId = deviceId,
                        payload = BridgeProtocol.payload(
                            ApprovalRequestPayload(
                                approvalId = approvalId,
                                tool = "apps.forceStop",
                                risk = "PRIVILEGED",
                                expiresInMillis = 120_000L,
                            )
                        ),
                    )
                )

                val response = client.get("/api/v1/devices/$deviceId/approvals") {
                    header(HttpHeaders.Authorization, "Bearer $ADMIN_TOKEN")
                }
                assertEquals(HttpStatusCode.OK, response.status)
                val raw = response.bodyAsText()
                assertFalse(raw.contains("displaySummary"))
                val approvals = BridgeProtocol.json.decodeFromString<List<RelayApprovalNotificationResponse>>(raw)
                assertEquals(1, approvals.size)
                assertEquals(deviceId, approvals.single().deviceId)
                assertEquals(approvalId, approvals.single().approvalId)
                assertEquals("apps.forceStop", approvals.single().tool)
                assertEquals("PRIVILEGED", approvals.single().risk)
                assertTrue(approvals.single().expiresAtEpochMillis > System.currentTimeMillis())

                val mutationAttempt = client.post("/api/v1/devices/$deviceId/approvals") {
                    header(HttpHeaders.Authorization, "Bearer $ADMIN_TOKEN")
                }
                assertEquals(HttpStatusCode.NotFound, mutationAttempt.status)
            }
        }

    @Test
    fun `unauthenticated websocket cannot publish approval notification`() = testApplication {
        application { relayModule(adminToken = ADMIN_TOKEN) }
        val wsClient = createClient { install(WebSockets) }

        wsClient.webSocket("/ws/device") {
            sendEnvelope(
                BridgeProtocol.envelope(
                    type = MessageType.APPROVAL_REQUEST,
                    deviceId = "device_attacker",
                    payload = BridgeProtocol.payload(
                        ApprovalRequestPayload(
                            approvalId = "approval_${UUID.randomUUID()}",
                            tool = "apps.forceStop",
                            risk = "PRIVILEGED",
                            expiresInMillis = 120_000L,
                        )
                    ),
                )
            )

            val errorEnvelope = receiveEnvelope()
            assertEquals(MessageType.ERROR, errorEnvelope.type)
            val error = BridgeProtocol.decodePayload<ErrorPayload>(errorEnvelope)
            assertEquals("not_authenticated", error.code)
        }
    }

    private suspend fun DefaultClientWebSocketSession.authenticate(
        pairingCode: String,
        keyPair: KeyPair,
    ): String {
        val publicKey = Base64.getEncoder().encodeToString(keyPair.public.encoded)
        sendEnvelope(
            BridgeProtocol.envelope(
                type = MessageType.PAIR_REQUEST,
                payload = BridgeProtocol.payload(
                    PairRequestPayload(
                        code = pairingCode,
                        publicKey = publicKey,
                        appVersion = "approval-route-test",
                        deviceLabel = "CI approval device",
                    )
                ),
            )
        )

        val pairOkEnvelope = receiveEnvelope()
        assertEquals(MessageType.PAIR_OK, pairOkEnvelope.type)
        val pairOk = BridgeProtocol.decodePayload<PairOkPayload>(pairOkEnvelope)
        val challengeEnvelope = receiveEnvelope()
        assertEquals(MessageType.AUTH_CHALLENGE, challengeEnvelope.type)
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
        return pairOk.deviceId
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
    }
}
