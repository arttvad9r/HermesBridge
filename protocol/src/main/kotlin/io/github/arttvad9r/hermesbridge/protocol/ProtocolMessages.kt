package io.github.arttvad9r.hermesbridge.protocol

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

const val PROTOCOL_VERSION = 1

object MessageType {
    const val PAIR_REQUEST = "pair.request"
    const val PAIR_OK = "pair.ok"
    const val SESSION_HELLO = "session.hello"
    const val AUTH_CHALLENGE = "auth.challenge"
    const val AUTH_RESPONSE = "auth.response"
    const val AUTH_OK = "auth.ok"
    const val HEARTBEAT_PING = "heartbeat.ping"
    const val HEARTBEAT_PONG = "heartbeat.pong"
    const val COMMAND_REQUEST = "command.request"
    const val COMMAND_RESULT = "command.result"
    const val ERROR = "error"
}

@Serializable
data class WireEnvelope(
    val v: Int = PROTOCOL_VERSION,
    val id: String,
    val type: String,
    val deviceId: String? = null,
    val timestamp: String,
    val payload: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class PairRequestPayload(
    val code: String,
    val publicKey: String,
    val appVersion: String,
    val protocolVersion: Int = PROTOCOL_VERSION,
    val deviceLabel: String,
)

@Serializable
data class PairOkPayload(val deviceId: String)

@Serializable
data class SessionHelloPayload(val deviceId: String)

@Serializable
data class AuthChallengePayload(val challenge: String)

@Serializable
data class AuthResponsePayload(val signature: String)

@Serializable
data class AuthOkPayload(val sessionId: String)

@Serializable
data class CommandRequestPayload(
    val tool: String,
    val requestId: String,
    val arguments: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class ProtocolError(
    val code: String,
    val message: String,
)

@Serializable
data class CommandResultPayload(
    val requestId: String,
    val ok: Boolean,
    val result: JsonObject? = null,
    val error: ProtocolError? = null,
)

@Serializable
data class ErrorPayload(
    val code: String,
    val message: String,
)

object BridgeProtocol {
    val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
        encodeDefaults = true
    }

    fun envelope(
        type: String,
        deviceId: String? = null,
        payload: JsonObject = JsonObject(emptyMap()),
    ): WireEnvelope = WireEnvelope(
        id = UUID.randomUUID().toString(),
        type = type,
        deviceId = deviceId,
        timestamp = Instant.now().toString(),
        payload = payload,
    )

    fun encode(envelope: WireEnvelope): String = json.encodeToString(envelope)

    fun decode(raw: String): WireEnvelope = json.decodeFromString(raw)

    inline fun <reified T> payload(value: T): JsonObject =
        json.encodeToJsonElement(value).jsonObject

    inline fun <reified T> decodePayload(envelope: WireEnvelope): T =
        json.decodeFromJsonElement(envelope.payload)

    fun authSigningBytes(deviceId: String, challenge: String): ByteArray =
        "$PROTOCOL_VERSION\n$deviceId\n$challenge".toByteArray(StandardCharsets.UTF_8)
}
