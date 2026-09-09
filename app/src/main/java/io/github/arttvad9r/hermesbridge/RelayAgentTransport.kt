package io.github.arttvad9r.hermesbridge

import android.content.Context
import android.os.Build
import io.github.arttvad9r.hermesbridge.protocol.AuthChallengePayload
import io.github.arttvad9r.hermesbridge.protocol.AuthResponsePayload
import io.github.arttvad9r.hermesbridge.protocol.BridgeProtocol
import io.github.arttvad9r.hermesbridge.protocol.CommandRequestPayload
import io.github.arttvad9r.hermesbridge.protocol.ErrorPayload
import io.github.arttvad9r.hermesbridge.protocol.MessageType
import io.github.arttvad9r.hermesbridge.protocol.PairOkPayload
import io.github.arttvad9r.hermesbridge.protocol.PairRequestPayload
import io.github.arttvad9r.hermesbridge.protocol.SessionHelloPayload
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class RelayAgentTransport(
    context: Context,
    private val relayWsUrl: String,
    healthRepository: DeviceHealthRepository,
) : AgentTransport {
    private val appContext = context.applicationContext
    private val identity = AndroidKeystoreDeviceIdentity()
    private val pairingStore = PairingStore(appContext)
    private val toolRegistry = BridgeToolRegistry(healthRepository)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = HttpClient(CIO) {
        install(WebSockets)
    }

    @Volatile
    private var connectionJob: Job? = null

    override suspend fun pair(code: String): Result<Unit> {
        if (!relayWsUrl.startsWith("wss://")) {
            return Result.failure(
                IllegalStateException("Relay URL must use wss:// in production builds.")
            )
        }
        connectionJob?.cancelAndJoin()
        val ready = CompletableDeferred<Result<Unit>>()
        connectionJob = scope.launch {
            connectionLoop(code, ready)
        }
        return runCatching {
            withTimeout(20_000L) { ready.await().getOrThrow() }
        }
    }

    override suspend fun disconnect(): Result<Unit> = runCatching {
        connectionJob?.cancelAndJoin()
        connectionJob = null
    }

    private suspend fun connectionLoop(
        initialPairingCode: String,
        ready: CompletableDeferred<Result<Unit>>,
    ) {
        var pairingCode: String? = initialPairingCode
        var backoffMillis = 1_000L

        while (currentCoroutineContext().isActive) {
            val authenticated = runCatching {
                runSession(pairingCode, ready)
            }.getOrElse { error ->
                if (!ready.isCompleted) {
                    ready.complete(Result.failure(error))
                    return
                }
                false
            }

            if (!ready.isCompleted) {
                ready.complete(
                    Result.failure(IllegalStateException("Relay connection closed before authentication."))
                )
                return
            }
            if (pairingStore.deviceId() == null) return

            pairingCode = null
            if (authenticated) backoffMillis = 1_000L
            val jitter = Random.nextLong(0L, min(1_000L, backoffMillis))
            delay(backoffMillis + jitter)
            backoffMillis = min(backoffMillis * 2L, 30_000L)
        }
    }

    private suspend fun runSession(
        pairingCode: String?,
        ready: CompletableDeferred<Result<Unit>>,
    ): Boolean {
        var authenticated = false

        client.webSocket(relayWsUrl) {
            val existingDeviceId = pairingStore.deviceId()
            if (existingDeviceId == null) {
                val code = pairingCode
                    ?: error("A pairing code is required for an unpaired device.")
                val pairPayload = PairRequestPayload(
                    code = code,
                    publicKey = identity.publicKeyBase64(),
                    appVersion = BuildConfig.VERSION_NAME,
                    deviceLabel = "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(80),
                )
                sendEnvelope(
                    type = MessageType.PAIR_REQUEST,
                    payload = BridgeProtocol.payload(pairPayload),
                )
            } else {
                sendEnvelope(
                    type = MessageType.SESSION_HELLO,
                    deviceId = existingDeviceId,
                    payload = BridgeProtocol.payload(SessionHelloPayload(existingDeviceId)),
                )
            }

            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val envelope = BridgeProtocol.decode(frame.readText())
                when (envelope.type) {
                    MessageType.PAIR_OK -> {
                        val payload = BridgeProtocol.decodePayload<PairOkPayload>(envelope)
                        if (envelope.deviceId != payload.deviceId) {
                            error("Relay returned inconsistent device identity.")
                        }
                        pairingStore.saveDeviceId(payload.deviceId)
                    }

                    MessageType.AUTH_CHALLENGE -> {
                        val deviceId = envelope.deviceId ?: pairingStore.deviceId()
                            ?: error("Authentication challenge has no device ID.")
                        if (pairingStore.deviceId() != deviceId) {
                            error("Authentication challenge targets another device.")
                        }
                        val payload = BridgeProtocol.decodePayload<AuthChallengePayload>(envelope)
                        val signature = identity.sign(
                            BridgeProtocol.authSigningBytes(deviceId, payload.challenge)
                        )
                        sendEnvelope(
                            type = MessageType.AUTH_RESPONSE,
                            deviceId = deviceId,
                            payload = BridgeProtocol.payload(AuthResponsePayload(signature)),
                        )
                    }

                    MessageType.AUTH_OK -> {
                        authenticated = true
                        if (!ready.isCompleted) ready.complete(Result.success(Unit))
                    }

                    MessageType.COMMAND_REQUEST -> {
                        val deviceId = pairingStore.deviceId()
                        if (!authenticated || deviceId == null || envelope.deviceId != deviceId) {
                            continue
                        }
                        val request = BridgeProtocol.decodePayload<CommandRequestPayload>(envelope)
                        val result = toolRegistry.execute(request)
                        sendEnvelope(
                            type = MessageType.COMMAND_RESULT,
                            deviceId = deviceId,
                            payload = BridgeProtocol.payload(result),
                        )
                    }

                    MessageType.HEARTBEAT_PING -> {
                        sendEnvelope(
                            type = MessageType.HEARTBEAT_PONG,
                            deviceId = pairingStore.deviceId(),
                        )
                    }

                    MessageType.ERROR -> {
                        val payload = BridgeProtocol.decodePayload<ErrorPayload>(envelope)
                        error("${payload.code}: ${payload.message}")
                    }
                }
            }
        }

        return authenticated
    }

    private suspend fun io.ktor.client.plugins.websocket.DefaultClientWebSocketSession.sendEnvelope(
        type: String,
        deviceId: String? = null,
        payload: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap()),
    ) {
        send(
            Frame.Text(
                BridgeProtocol.encode(
                    BridgeProtocol.envelope(type, deviceId, payload)
                )
            )
        )
    }
}
