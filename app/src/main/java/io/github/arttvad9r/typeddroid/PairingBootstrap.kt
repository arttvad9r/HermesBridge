package io.github.arttvad9r.typeddroid

import io.github.arttvad9r.typeddroid.protocol.BridgeProtocol
import io.github.arttvad9r.typeddroid.protocol.TransportEndpointHint
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString

internal data class PairingBootstrap(
    val code: String,
    val endpoints: List<TransportEndpointHint>,
)

@Serializable
private data class PairingBootstrapPayload(
    val v: Int,
    val code: String,
    val expiresAtEpochMillis: Long,
    val endpoints: List<PairingBootstrapEndpointPayload>,
)

@Serializable
private data class PairingBootstrapEndpointPayload(
    val webSocketUrl: String,
    val certificateSha256: String,
)

internal fun parsePairingBootstrapInput(
    value: String,
    nowMillis: Long = System.currentTimeMillis(),
): PairingBootstrap? {
    val input = normalizePairingInput(value)
    if (PairingCodeValidator.isValid(input)) {
        return PairingBootstrap(PairingCodeValidator.normalize(input), emptyList())
    }
    if (input.length !in PAIRING_BUNDLE_PREFIX.length + 1..MAX_PAIRING_BUNDLE_CHARS) return null
    if (!input.startsWith(PAIRING_BUNDLE_PREFIX)) return null
    val encoded = input.removePrefix(PAIRING_BUNDLE_PREFIX)
    if (encoded.isBlank() || encoded.any { !(it.isLetterOrDigit() || it == '-' || it == '_') }) return null
    val padding = "=".repeat((4 - encoded.length % 4) % 4)
    val json = runCatching {
        Base64.getUrlDecoder().decode(encoded + padding).decodeToString()
    }.getOrNull() ?: return null
    if (json.length > MAX_PAIRING_BUNDLE_JSON_CHARS) return null
    val payload = runCatching {
        BridgeProtocol.json.decodeFromString<PairingBootstrapPayload>(json)
    }.getOrNull() ?: return null
    if (payload.v != PAIRING_BUNDLE_VERSION) return null
    val code = PairingCodeValidator.normalize(payload.code)
    if (!PairingCodeValidator.isValid(code)) return null
    if (payload.expiresAtEpochMillis <= nowMillis || payload.expiresAtEpochMillis > nowMillis + MAX_PAIRING_BUNDLE_FUTURE_MILLIS) {
        return null
    }
    if (payload.endpoints.isEmpty() || payload.endpoints.size > MAX_PAIRING_BOOTSTRAP_ENDPOINTS) return null
    val hints = payload.endpoints.mapNotNull { endpoint ->
        validateDirectTransportHint(
            TransportEndpointHint(
                webSocketUrl = endpoint.webSocketUrl,
                certificateSha256 = endpoint.certificateSha256,
                expiresAtEpochMillis = payload.expiresAtEpochMillis,
            ),
            nowMillis,
        )?.let {
            TransportEndpointHint(
                webSocketUrl = it.endpoint.webSocketUrl,
                certificateSha256 = requireNotNull(it.certificateSha256),
                expiresAtEpochMillis = payload.expiresAtEpochMillis,
            )
        }
    }.distinctBy(TransportEndpointHint::webSocketUrl)
    if (hints.size != payload.endpoints.size) return null
    return PairingBootstrap(code, hints)
}

internal fun normalizePairingInput(value: String): String {
    var input = value.trim()
    if (input.length >= 2) {
        val first = input.first()
        val last = input.last()
        if ((first == '`' && last == '`') || (first == '"' && last == '"') || (first == '\'' && last == '\'')) {
            input = input.substring(1, input.length - 1).trim()
        }
    }
    val compact = input.filterNot(Char::isWhitespace)
    return if (compact.startsWith(PAIRING_BUNDLE_PREFIX)) compact else compact.uppercase()
}

internal const val PAIRING_BUNDLE_PREFIX = "typeddroid://pair/"
internal const val MAX_PAIRING_BUNDLE_CHARS = 16_384
private const val MAX_PAIRING_BUNDLE_JSON_CHARS = 12_000
private const val PAIRING_BUNDLE_VERSION = 1
private const val MAX_PAIRING_BOOTSTRAP_ENDPOINTS = 8
private const val MAX_PAIRING_BUNDLE_FUTURE_MILLIS = 10L * 60L * 1000L
