package io.github.arttvad9r.hermesbridge

import java.net.URI

internal data class RelayEndpoint(
    val webSocketUrl: String,
    val artifactBaseUrl: String,
)

internal fun parseRelayEndpoint(value: String): RelayEndpoint {
    require(value.length in 1..2048) { "Relay URL length is invalid." }
    val uri = runCatching { URI(value) }
        .getOrElse { throw IllegalArgumentException("Relay URL is invalid.", it) }

    require(!uri.isOpaque) { "Relay URL must be hierarchical." }
    require(uri.scheme == "wss") { "Relay URL must use wss://." }
    require(!uri.host.isNullOrBlank()) { "Relay URL must contain a host." }
    require(uri.userInfo == null) { "Relay URL must not contain user info." }
    require(uri.rawQuery == null) { "Relay URL must not contain a query." }
    require(uri.rawFragment == null) { "Relay URL must not contain a fragment." }
    require(uri.rawPath == RELAY_WS_PATH) { "Relay WebSocket path must be $RELAY_WS_PATH." }
    require(uri.port == -1 || uri.port in 1..65535) { "Relay URL port is invalid." }

    val canonicalWebSocket = URI(
        "wss",
        null,
        uri.host,
        uri.port,
        RELAY_WS_PATH,
        null,
        null,
    ).toASCIIString()
    val artifactBase = URI(
        "https",
        null,
        uri.host,
        uri.port,
        ARTIFACT_PATH,
        null,
        null,
    ).toASCIIString().removeSuffix("/")

    return RelayEndpoint(
        webSocketUrl = canonicalWebSocket,
        artifactBaseUrl = artifactBase,
    )
}

internal const val RELAY_WS_PATH = "/ws/device"
private const val ARTIFACT_PATH = "/device-artifacts"
