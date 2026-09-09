package io.github.arttvad9r.hermesbridge.relay

import io.github.arttvad9r.hermesbridge.protocol.AuthChallengePayload
import io.github.arttvad9r.hermesbridge.protocol.AuthOkPayload
import io.github.arttvad9r.hermesbridge.protocol.AuthResponsePayload
import io.github.arttvad9r.hermesbridge.protocol.BridgeProtocol
import io.github.arttvad9r.hermesbridge.protocol.CommandRequestPayload
import io.github.arttvad9r.hermesbridge.protocol.CommandResultPayload
import io.github.arttvad9r.hermesbridge.protocol.DeviceRevokeOkPayload
import io.github.arttvad9r.hermesbridge.protocol.ErrorPayload
import io.github.arttvad9r.hermesbridge.protocol.MessageType
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
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
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
import io.ktor.utils.io.readAvailable
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable

@Serializable
data class RelayDeviceResponse(
    val deviceId: String,
    val label: String,
    val connected: Boolean,
)

@Serializable
data class RelayCommandRequest(
    val tool: String,
    val arguments: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap()),
)

private class DeviceSessionHub {
    private val sessions = ConcurrentHashMap<String, DefaultWebSocketServerSession>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<CommandResultPayload>>()

    fun register(deviceId: String, session: DefaultWebSocketServerSession) {
        sessions[deviceId] = session
    }

    fun unregister(deviceId: String, session: DefaultWebSocketServerSession) {
        sessions.remove(deviceId, session)
    }

    fun isConnected(deviceId: String): Boolean = sessions.containsKey(deviceId)

    suspend fun revoke(deviceId: String) {
        val session = sessions.remove(deviceId) ?: return
        try {
            session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Device pairing revoked"))
        } catch (_: Exception) {
            // Revocation is already effective once the session is removed and registry key is deleted.
        }
    }

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

internal fun commandTimeoutMillis(tool: String): Long = when (tool) {
    "apps.install" -> 300_000L
    "files.analyze",
    "battery.usage",
    "apps.revokePermission",
    -> 60_000L
    else -> 20_000L
}

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

        post("/api/v1/devices/{deviceId}/revoke") {
            if (!call.requireAdmin(adminToken)) return@post
            val deviceId = call.parameters["deviceId"]
            if (deviceId == null || !DEVICE_ID_REGEX.matches(deviceId)) {
                call.respondText(
                    "{\"error\":\"invalid_device_id\"}",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                return@post
            }

            val revoked = runtime.devices.revoke(deviceId)
            if (!revoked) {
                call.respondText(
                    "{\"error\":\"device_not_found\"}",
                    ContentType.Application.Json,
                    HttpStatusCode.NotFound,
                )
                return@post
            }
            runtime.sessions.revoke(deviceId)
            call.respondText(
                "{\"revoked\":true}",
                ContentType.Application.Json,
                HttpStatusCode.OK,
            )
        }

        post("/api/v1/pairing-codes") {
            if (!call.requireAdmin(adminToken)) return@post
            val issued = runtime.pairingCodes.issue()
            call.respondText(
                BridgeProtocol.json.encodeToString(issued),
                ContentType.Application.Json,
                HttpStatusCode.Created,
            )
        }

        post("/api/v1/apk-artifacts") {
            if (!call.requireAdmin(adminToken)) return@post
            val declaredLength = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()
            if (declaredLength == null || declaredLength !in 1..MAX_APK_ARTIFACT_BYTES) {
                call.respondText(
                    "{\"error\":\"invalid_apk_size\"}",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                return@post
            }
            val fileName = call.request.header(APK_NAME_HEADER)
            if (fileName == null || !isSafeApkName(fileName)) {
                call.respondText(
                    "{\"error\":\"invalid_apk_name\"}",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                return@post
            }

            val staged = try {
                runtime.apkArtifacts.stage(
                    fileName = fileName,
                    declaredLength = declaredLength,
                    channel = call.receiveChannel(),
                )
            } catch (_: ApkArtifactTooLargeException) {
                call.respondText(
                    "{\"error\":\"apk_too_large\"}",
                    ContentType.Application.Json,
                    HttpStatusCode.PayloadTooLarge,
                )
                return@post
            } catch (_: Exception) {
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
    val supplied = request.header(HttpHeaders.Authorization)
        ?.removePrefix("Bearer ")
        ?.takeIf { it.isNotEmpty() }
        ?: run {
            respondText("Unauthorized", ContentType.Text.Plain, HttpStatusCode.Unauthorized)
            return false
        }
    if (!MessageDigest.isEqual(supplied.toByteArray(), adminToken.toByteArray())) {
        respondText("Unauthorized", ContentType.Text.Plain, HttpStatusCode.Unauthorized)
        return false
    }
    return true
}

private suspend fun DefaultWebSocketServerSession.handleDeviceSocket(runtime: RelayRuntime) {
    var authenticatedDeviceId: String? = null
    var challenge: String? = null

    try {
        for (frame in incoming) {
            if (frame !is Frame.Text) continue
            val text = frame.readText()
            val envelope = try {
                BridgeProtocol.decode(text)
            } catch (_: Exception) {
                sendError("invalid_envelope", "Invalid protocol envelope.")
                continue
            }

            when (envelope.type) {
                MessageType.PAIR_REQUEST -> {
                    if (authenticatedDeviceId != null) {
                        sendError("already_authenticated", "Session is already authenticated.")
                        continue
                    }
                    val payload = try {
                        BridgeProtocol.decodePayload<PairRequestPayload>(envelope)
                    } catch (_: Exception) {
                        sendError("invalid_payload", "Invalid pairing payload.")
                        continue
                    }
                    val pairingCode = runtime.pairingCodes.consume(payload.code)
                    if (pairingCode == null) {
                        sendError("invalid_pairing_code", "Pairing code is invalid or expired.")
                        continue
                    }
                    val device = try {
                        runtime.devices.register(
                            publicKey = payload.publicKey,
                            label = payload.deviceLabel,
                            appVersion = payload.appVersion,
                        )
                    } catch (_: Exception) {
                        sendError("invalid_device", "Device identity could not be registered.")
                        continue
                    }
                    sendEnvelope(
                        BridgeProtocol.envelope(
                            type = MessageType.PAIR_OK,
                            deviceId = device.deviceId,
                            payload = BridgeProtocol.payload(PairOkPayload(device.deviceId)),
                        )
                    )
                    challenge = AuthCrypto.randomChallenge()
                    sendEnvelope(
                        BridgeProtocol.envelope(
                            type = MessageType.AUTH_CHALLENGE,
                            deviceId = device.deviceId,
                            payload = BridgeProtocol.payload(AuthChallengePayload(challenge!!)),
                        )
                    )
                }

                MessageType.SESSION_HELLO -> {
                    if (authenticatedDeviceId != null) {
                        sendError("already_authenticated", "Session is already authenticated.")
                        continue
                    }
                    val payload = try {
                        BridgeProtocol.decodePayload<SessionHelloPayload>(envelope)
                    } catch (_: Exception) {
                        sendError("invalid_payload", "Invalid session hello.")
                        continue
                    }
                    val device = runtime.devices.find(payload.deviceId)
                    if (device == null || envelope.deviceId != payload.deviceId) {
                        sendError("unknown_device", "Device is not paired with this relay.")
                        continue
                    }
                    challenge = AuthCrypto.randomChallenge()
                    sendEnvelope(
                        BridgeProtocol.envelope(
                            type = MessageType.AUTH_CHALLENGE,
                            deviceId = device.deviceId,
                            payload = BridgeProtocol.payload(AuthChallengePayload(challenge!!)),
                        )
                    )
                }

                MessageType.AUTH_RESPONSE -> {
                    val deviceId = envelope.deviceId
                    val nonce = challenge
                    val device = deviceId?.let(runtime.devices::find)
                    if (deviceId == null || nonce == null || device == null) {
                        sendError("auth_not_started", "Start pairing or session authentication first.")
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
                        sendError("auth_failed", "Device signature verification failed")
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

                MessageType.DEVICE_REVOKE_REQUEST -> {
                    val deviceId = authenticatedDeviceId
                    if (
                        deviceId == null ||
                        envelope.deviceId != deviceId ||
                        envelope.payload.isNotEmpty()
                    ) {
                        sendError("not_authenticated", "Only the authenticated device may revoke its own pairing.")
                        continue
                    }

                    if (!runtime.devices.revoke(deviceId)) {
                        sendError("unknown_device", "Device is no longer registered.")
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unknown device"))
                        return
                    }

                    runtime.sessions.unregister(deviceId, this)
                    authenticatedDeviceId = null
                    sendEnvelope(
                        BridgeProtocol.envelope(
                            type = MessageType.DEVICE_REVOKE_OK,
                            deviceId = deviceId,
                            payload = BridgeProtocol.payload(DeviceRevokeOkPayload(deviceId)),
                        )
                    )
                    close(CloseReason(CloseReason.Codes.NORMAL, "Device pairing revoked by device"))
                    return
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
private val DEVICE_ID_REGEX = Regex("^device_[A-Za-z0-9-]{1,80}$")
