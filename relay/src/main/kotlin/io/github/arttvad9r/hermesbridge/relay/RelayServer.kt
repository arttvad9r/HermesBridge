package io.github.arttvad9r.hermesbridge.relay

import io.github.arttvad9r.hermesbridge.protocol.AuthChallengePayload
import io.github.arttvad9r.hermesbridge.protocol.AuthOkPayload
import io.github.arttvad9r.hermesbridge.protocol.AuthResponsePayload
import io.github.arttvad9r.hermesbridge.protocol.BridgeProtocol
import io.github.arttvad9r.hermesbridge.protocol.CommandRequestPayload
import io.github.arttvad9r.hermesbridge.protocol.CommandResultPayload
import io.github.arttvad9r.hermesbridge.protocol.ErrorPayload
import io.github.arttvad9r.hermesbridge.protocol.MessageType
import io.github.arttvad9r.hermesbridge.protocol.PROTOCOL_VERSION
import io.github.arttvad9r.hermesbridge.protocol.PairOkPayload
import io.github.arttvad9r.hermesbridge.protocol.PairRequestPayload
import io.github.arttvad9r.hermesbridge.protocol.ProtocolError
import io.github.arttvad9r.hermesbridge.protocol.SessionHelloPayload
import io.github.arttvad9r.hermesbridge.protocol.WireEnvelope
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveStream
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject

@Serializable
data class PairingCodeResponse(
    val code: String,
    val expiresAtEpochMillis: Long,
)

@Serializable
data class RelayCommandRequest(
    val tool: String,
    val arguments: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class RelayDeviceResponse(
    val deviceId: String,
    val label: String,
    val connected: Boolean,
)

private class DeviceSessionHub {
    private val sessions = ConcurrentHashMap<String, DefaultWebSocketServerSession>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<CommandResultPayload>>()

    suspend fun register(deviceId: String, session: DefaultWebSocketServerSession) {
        val old = sessions.put(deviceId, session)
        if (old != null && old !== session) {
            try {
                old.close(CloseReason(CloseReason.Codes.NORMAL, "Replaced by a newer session"))
            } catch (_: Exception) {
                // The old socket may already be closed. The new authenticated session wins.
            }
        }
    }

    fun unregister(deviceId: String, session: DefaultWebSocketServerSession) {
        sessions.remove(deviceId, session)
    }

    fun isConnected(deviceId: String): Boolean = sessions.containsKey(deviceId)

    suspend fun sendCommand(
        deviceId: String,
        request: RelayCommandRequest,
    ): CommandResultPayload {
        val session = sessions[deviceId]
            ?: return CommandResultPayload(
                requestId = "",
                ok = false,
                error = ProtocolError("device_offline", "Device is not connected."),
            )
        val requestId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<CommandResultPayload>()
        pending[requestId] = deferred
        try {
            val payload = CommandRequestPayload(
                tool = request.tool,
                requestId = requestId,
                arguments = request.arguments,
            )
            val envelope = BridgeProtocol.envelope(
                type = MessageType.COMMAND_REQUEST,
                deviceId = deviceId,
                payload = BridgeProtocol.payload(payload),
            )
            session.send(Frame.Text(BridgeProtocol.encode(envelope)))
            return withTimeout(commandTimeoutMillis(request.tool)) { deferred.await() }
        } finally {
            pending.remove(requestId)
        }
    }

    fun complete(result: CommandResultPayload) {
        pending[result.requestId]?.complete(result)
    }
}

private class RelayRuntime(
    deviceRegistryPath: Path?,
    artifactDirectory: Path?,
) {
    val pairingCodes = PairingCodeStore()
    val devices = DeviceRegistry(deviceRegistryPath)
    val sessions = DeviceSessionHub()
    val apkArtifacts = ApkArtifactStore(artifactDirectory)
}

internal fun commandHttpStatus(result: CommandResultPayload): HttpStatusCode = when {
    result.ok -> HttpStatusCode.OK
    result.error?.code == "device_offline" -> HttpStatusCode.ServiceUnavailable
    result.error?.code == "command_timeout" -> HttpStatusCode.GatewayTimeout
    else -> HttpStatusCode.OK
}

internal fun commandTimeoutMillis(tool: String): Long =
    if (tool == "apps.install") 300_000L else 20_000L

fun main() {
    val token = System.getenv("HERMES_BRIDGE_ADMIN_TOKEN")
        ?.takeIf { it.length >= 24 }
        ?: error("HERMES_BRIDGE_ADMIN_TOKEN must be set to a secret with at least 24 characters")
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val stateDirectory = Path.of(
        System.getenv("HERMES_BRIDGE_STATE_DIR")
            ?.takeIf { it.isNotBlank() }
            ?: ".hermes-bridge",
    )

    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        relayModule(
            adminToken = token,
            deviceRegistryPath = stateDirectory.resolve("devices.json"),
            artifactDirectory = stateDirectory.resolve("apk-artifacts"),
        )
    }.start(wait = true)
}

fun Application.relayModule(
    adminToken: String,
    deviceRegistryPath: Path? = null,
    artifactDirectory: Path? = null,
) {
    val runtime = RelayRuntime(deviceRegistryPath, artifactDirectory)

    install(WebSockets) {
        pingPeriodMillis = 20_000L
        timeoutMillis = 30_000L
        maxFrameSize = 256 * 1024L
        masking = false
    }

    routing {
        get("/health") {
            call.respondText("ok", ContentType.Text.Plain)
        }

        get("/api/v1/devices") {
            if (!call.requireAdmin(adminToken)) return@get
            val devices = runtime.devices.list().map { device ->
                RelayDeviceResponse(
                    deviceId = device.deviceId,
                    label = device.label,
                    connected = runtime.sessions.isConnected(device.deviceId),
                )
            }
            call.respondText(
                BridgeProtocol.json.encodeToString(devices),
                ContentType.Application.Json,
            )
        }

        post("/api/v1/pairing-codes") {
            if (!call.requireAdmin(adminToken)) return@post
            val pairing = runtime.pairingCodes.create()
            val body = BridgeProtocol.json.encodeToString(
                PairingCodeResponse(pairing.code, pairing.expiresAtEpochMillis)
            )
            call.respondText(body, ContentType.Application.Json)
        }

        post("/api/v1/apk-artifacts") {
            if (!call.requireAdmin(adminToken)) return@post
            val fileName = call.request.headers[APK_NAME_HEADER]
            if (fileName.isNullOrBlank()) {
                call.respondText(
                    "{\"error\":\"missing_apk_name\"}",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                return@post
            }

            val declaredLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (declaredLength != null && declaredLength !in 1..ApkArtifactStore.MAX_APK_BYTES) {
                call.respondText(
                    "{\"error\":\"apk_too_large\"}",
                    ContentType.Application.Json,
                    HttpStatusCode.PayloadTooLarge,
                )
                return@post
            }

            val staged = try {
                call.receiveStream().use { input ->
                    withContext(Dispatchers.IO) {
                        runtime.apkArtifacts.stage(fileName, input, declaredLength)
                    }
                }
            } catch (error: ApkArtifactTooLargeException) {
                call.respondText(
                    "{\"error\":\"apk_too_large\"}",
                    ContentType.Application.Json,
                    HttpStatusCode.PayloadTooLarge,
                )
                return@post
            } catch (error: InvalidApkArtifactException) {
                call.respondText(
                    "{\"error\":\"invalid_apk_artifact\"}",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                return@post
            }

            call.respondText(
                BridgeProtocol.json.encodeToString(staged),
                ContentType.Application.Json,
                HttpStatusCode.Created,
            )
        }

        get("/device-artifacts/{artifactId}") {
            val artifactId = call.parameters["artifactId"]
            val suppliedToken = call.request.headers[HttpHeaders.Authorization]
                ?.removePrefix("Bearer ")
                ?.takeIf { it.isNotBlank() }
            if (artifactId.isNullOrBlank() || suppliedToken == null) {
                call.respondText("Not found", ContentType.Text.Plain, HttpStatusCode.NotFound)
                return@get
            }

            val artifact = runtime.apkArtifacts.findAuthorized(artifactId, suppliedToken)
            if (artifact == null) {
                call.respondText("Not found", ContentType.Text.Plain, HttpStatusCode.NotFound)
                return@get
            }

            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.response.header("X-Content-Type-Options", "nosniff")
            call.respondFile(file = artifact.path.toFile())
        }

        post("/api/v1/devices/{deviceId}/commands") {
            if (!call.requireAdmin(adminToken)) return@post
            val deviceId = call.parameters["deviceId"]
            if (deviceId.isNullOrBlank()) {
                call.respondText(
                    "{\"error\":\"missing_device_id\"}",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                return@post
            }

            val request = try {
                BridgeProtocol.json.decodeFromString<RelayCommandRequest>(call.receiveText())
            } catch (_: Exception) {
                call.respondText(
                    "{\"error\":\"invalid_request\"}",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                return@post
            }

            val result = try {
                runtime.sessions.sendCommand(deviceId, request)
            } catch (_: Exception) {
                CommandResultPayload(
                    requestId = "",
                    ok = false,
                    error = ProtocolError("command_timeout", "Device did not return a result in time."),
                )
            }
            call.respondText(
                BridgeProtocol.json.encodeToString(result),
                ContentType.Application.Json,
                commandHttpStatus(result),
            )
        }

        webSocket("/ws/device") {
            handleDeviceSocket(runtime)
        }
    }
}

private suspend fun ApplicationCall.requireAdmin(adminToken: String): Boolean {
    val supplied = request.headers[HttpHeaders.Authorization]
    if (supplied == "Bearer $adminToken") return true
    respondText("Unauthorized", ContentType.Text.Plain, HttpStatusCode.Unauthorized)
    return false
}

private suspend fun DefaultWebSocketServerSession.handleDeviceSocket(runtime: RelayRuntime) {
    var pendingDevice: DeviceRecord? = null
    var challenge: String? = null
    var authenticatedDeviceId: String? = null

    try {
        for (frame in incoming) {
            if (frame !is Frame.Text) continue

            val envelope = try {
                BridgeProtocol.decode(frame.readText())
            } catch (_: Exception) {
                sendError("invalid_json", "Message is not a valid protocol envelope.")
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Invalid envelope"))
                return
            }

            if (envelope.v != PROTOCOL_VERSION) {
                sendError("unsupported_version", "Unsupported protocol version.")
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Unsupported protocol"))
                return
            }

            when (envelope.type) {
                MessageType.PAIR_REQUEST -> {
                    if (pendingDevice != null || authenticatedDeviceId != null) {
                        sendError("invalid_state", "Pairing is not valid in the current session state.")
                        continue
                    }

                    val payload = try {
                        BridgeProtocol.decodePayload<PairRequestPayload>(envelope)
                    } catch (_: Exception) {
                        sendError("invalid_payload", "Invalid pairing payload.")
                        continue
                    }

                    if (payload.protocolVersion != PROTOCOL_VERSION) {
                        sendError("unsupported_version", "Unsupported pairing protocol version.")
                        continue
                    }

                    val publicKey = try {
                        AuthCrypto.decodeEcPublicKey(payload.publicKey)
                    } catch (_: Exception) {
                        sendError("invalid_public_key", "Device public key is invalid.")
                        continue
                    }

                    if (!runtime.pairingCodes.consume(payload.code)) {
                        sendError("invalid_pairing_code", "Pairing code is invalid or expired.")
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid pairing code"))
                        return
                    }

                    val device = runtime.devices.register(publicKey, payload.deviceLabel)
                    pendingDevice = device
                    sendEnvelope(
                        BridgeProtocol.envelope(
                            type = MessageType.PAIR_OK,
                            deviceId = device.deviceId,
                            payload = BridgeProtocol.payload(PairOkPayload(device.deviceId)),
                        )
                    )
                    val newChallenge = AuthCrypto.newChallenge()
                    challenge = newChallenge
                    sendEnvelope(
                        BridgeProtocol.envelope(
                            type = MessageType.AUTH_CHALLENGE,
                            deviceId = device.deviceId,
                            payload = BridgeProtocol.payload(AuthChallengePayload(newChallenge)),
                        )
                    )
                }

                MessageType.SESSION_HELLO -> {
                    if (pendingDevice != null || authenticatedDeviceId != null) {
                        sendError("invalid_state", "Session hello is not valid in the current state.")
                        continue
                    }

                    val payload = try {
                        BridgeProtocol.decodePayload<SessionHelloPayload>(envelope)
                    } catch (_: Exception) {
                        sendError("invalid_payload", "Invalid session hello payload.")
                        continue
                    }

                    val device = runtime.devices.find(payload.deviceId)
                    if (device == null || envelope.deviceId != payload.deviceId) {
                        sendError("unknown_device", "Device is not registered.")
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unknown device"))
                        return
                    }

                    pendingDevice = device
                    val newChallenge = AuthCrypto.newChallenge()
                    challenge = newChallenge
                    sendEnvelope(
                        BridgeProtocol.envelope(
                            type = MessageType.AUTH_CHALLENGE,
                            deviceId = device.deviceId,
                            payload = BridgeProtocol.payload(AuthChallengePayload(newChallenge)),
                        )
                    )
                }

                MessageType.AUTH_RESPONSE -> {
                    val device = pendingDevice
                    val nonce = challenge
                    if (device == null || nonce == null || authenticatedDeviceId != null) {
                        sendError("invalid_state", "Authentication challenge is not active.")
                        continue
                    }

                    val payload = try {
                        BridgeProtocol.decodePayload<AuthResponsePayload>(envelope)
                    } catch (_: Exception) {
                        sendError("invalid_payload", "Invalid authentication response.")
                        continue
                    }

                    if (
                        envelope.deviceId != device.deviceId ||
                        !AuthCrypto.verify(device, nonce, payload.signature)
                    ) {
                        sendError("auth_failed", "Device signature verification failed.")
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Authentication failed"))
                        return
                    }

                    authenticatedDeviceId = device.deviceId
                    challenge = null
                    runtime.sessions.register(device.deviceId, this)
                    sendEnvelope(
                        BridgeProtocol.envelope(
                            type = MessageType.AUTH_OK,
                            deviceId = device.deviceId,
                            payload = BridgeProtocol.payload(AuthOkPayload(UUID.randomUUID().toString())),
                        )
                    )
                }

                MessageType.COMMAND_RESULT -> {
                    val deviceId = authenticatedDeviceId
                    if (deviceId == null || envelope.deviceId != deviceId) {
                        sendError("not_authenticated", "Authenticate before returning command results.")
                        continue
                    }

                    val payload = try {
                        BridgeProtocol.decodePayload<CommandResultPayload>(envelope)
                    } catch (_: Exception) {
                        sendError("invalid_payload", "Invalid command result payload.")
                        continue
                    }
                    runtime.sessions.complete(payload)
                }

                MessageType.HEARTBEAT_PONG -> Unit

                else -> sendError("unexpected_message", "Message type is not accepted in this direction.")
            }
        }
    } finally {
        authenticatedDeviceId?.let { runtime.sessions.unregister(it, this) }
    }
}

private suspend fun DefaultWebSocketServerSession.sendEnvelope(envelope: WireEnvelope) {
    send(Frame.Text(BridgeProtocol.encode(envelope)))
}

private suspend fun DefaultWebSocketServerSession.sendError(code: String, message: String) {
    sendEnvelope(
        BridgeProtocol.envelope(
            type = MessageType.ERROR,
            payload = BridgeProtocol.payload(ErrorPayload(code, message)),
        )
    )
}

private const val APK_NAME_HEADER = "X-Hermes-Apk-Name"
