package io.github.arttvad9r.hermesbridge.relay

import io.github.arttvad9r.hermesbridge.protocol.BridgeProtocol
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
import java.security.KeyPairGenerator
import java.util.Base64
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class RelayPairingPreflightTest {
    @Test
    fun productionRelayBindsLoopbackOnly() {
        assertEquals("127.0.0.1", RELAY_BIND_HOST)
    }

    @Test
    fun pairingThatDisconnectsBeforeAuthenticationDoesNotLeaveTrustedDevice() = testApplication {
        val directory = Files.createTempDirectory("hermes-bridge-provisional-pair")
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
        val wsClient = createClient { install(WebSockets) }
        var provisionalDeviceId: String? = null

        wsClient.webSocket("/ws/device") {
            sendEnvelope(
                BridgeProtocol.envelope(
                    type = MessageType.PAIR_REQUEST,
                    payload = BridgeProtocol.payload(
                        PairRequestPayload(
                            code = pairing.code,
                            publicKey = publicKey,
                            appVersion = "preflight-test",
                            deviceLabel = "Interrupted pair",
                        )
                    ),
                )
            )

            val pairOkEnvelope = receiveEnvelope()
            assertEquals(MessageType.PAIR_OK, pairOkEnvelope.type)
            provisionalDeviceId = BridgeProtocol.decodePayload<PairOkPayload>(pairOkEnvelope).deviceId
            assertNotNull(provisionalDeviceId)

            val challengeEnvelope = receiveEnvelope()
            assertEquals(MessageType.AUTH_CHALLENGE, challengeEnvelope.type)
            // Exit without AUTH_RESPONSE. The relay must roll back the provisional registry record.
        }

        val devicesResponse = client.get("/api/v1/devices") {
            header(HttpHeaders.Authorization, "Bearer $ADMIN_TOKEN")
        }
        assertEquals(HttpStatusCode.OK, devicesResponse.status)
        val devices = BridgeProtocol.json.decodeFromString<List<RelayDeviceResponse>>(
            devicesResponse.bodyAsText()
        )
        assertFalse(devices.any { it.deviceId == provisionalDeviceId })
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
