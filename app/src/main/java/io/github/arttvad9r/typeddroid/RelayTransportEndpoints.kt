package io.github.arttvad9r.typeddroid

import android.content.Context
import io.github.arttvad9r.typeddroid.protocol.BridgeProtocol
import io.github.arttvad9r.typeddroid.protocol.TransportEndpointHint
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import java.net.Inet6Address
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString


internal const val DIRECT_TLS_SERVER_NAME = "typeddroid-direct"

internal fun createRelayWebSocketHttpClient(certificateSha256: String?): HttpClient = HttpClient(CIO) {
    if (certificateSha256 != null) {
        engine {
            https {
                trustManager = PinnedCertificateTrustManager(certificateSha256)
                serverName = DIRECT_TLS_SERVER_NAME
            }
        }
    }
    install(WebSockets) {
        applyRelayWebSocketPolicy()
    }
}

internal data class RelayConnectionEndpoint(
    val endpoint: RelayEndpoint,
    val certificateSha256: String? = null,
    val source: RelayEndpointSource = RelayEndpointSource.CONFIGURED,
) {
    val isPinnedDirect: Boolean get() = certificateSha256 != null
}


internal class ActiveRelayEndpointRuntime(initial: RelayConnectionEndpoint) {
    @Volatile
    private var currentEndpoint: RelayConnectionEndpoint = initial

    fun current(): RelayConnectionEndpoint = currentEndpoint

    fun update(endpoint: RelayConnectionEndpoint) {
        currentEndpoint = endpoint
    }
}

internal fun openRelayHttpConnection(
    url: URL,
    endpoint: RelayConnectionEndpoint,
): HttpURLConnection {
    val connection = url.openConnection() as HttpURLConnection
    val pin = endpoint.certificateSha256 ?: return connection
    require(connection is HttpsURLConnection) { "Pinned relay endpoint must use HTTPS." }
    val trustManager = PinnedCertificateTrustManager(pin)
    val sslContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(trustManager), SecureRandom())
    }
    connection.sslSocketFactory = sslContext.socketFactory
    connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, session ->
        val leaf = runCatching { session.peerCertificates.firstOrNull() as? X509Certificate }.getOrNull()
        leaf != null && runCatching {
            leaf.checkValidity()
            MessageDigest.isEqual(
                pin.encodeToByteArray(),
                certificateSha256(leaf).encodeToByteArray(),
            )
        }.getOrDefault(false)
    }
    return connection
}

internal enum class RelayEndpointSource {
    DIRECT,
    CONFIGURED,
}

internal class RelayEndpointHintStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "typed_droid_transport_hints",
        Context.MODE_PRIVATE,
    )

    fun load(nowMillis: Long = System.currentTimeMillis()): List<TransportEndpointHint> {
        val raw = preferences.getString(KEY_HINTS, null) ?: return emptyList()
        val stored = runCatching {
            BridgeProtocol.json.decodeFromString<List<StoredTransportHint>>(raw)
        }.getOrElse {
            clear()
            return emptyList()
        }
        val valid = stored.mapNotNull { item ->
            item.toProtocolHint().takeIf { hint ->
                hint.expiresAtEpochMillis > nowMillis && validateDirectTransportHint(hint, nowMillis) != null
            }
        }.distinctBy { it.webSocketUrl }.take(MAX_STORED_HINTS)
        if (valid.size != stored.size) save(valid)
        return valid
    }

    fun replace(hints: List<TransportEndpointHint>, nowMillis: Long = System.currentTimeMillis()) {
        val valid = hints.filter { hint ->
            validateDirectTransportHint(hint, nowMillis) != null
        }.distinctBy { it.webSocketUrl }.take(MAX_STORED_HINTS)
        save(valid)
    }

    fun clear() {
        preferences.edit().remove(KEY_HINTS).apply()
    }

    private fun save(hints: List<TransportEndpointHint>) {
        if (hints.isEmpty()) {
            clear()
            return
        }
        val encoded = BridgeProtocol.json.encodeToString(
            hints.map { StoredTransportHint(it.webSocketUrl, it.certificateSha256, it.expiresAtEpochMillis) }
        )
        preferences.edit().putString(KEY_HINTS, encoded).apply()
    }

    @Serializable
    private data class StoredTransportHint(
        val webSocketUrl: String,
        val certificateSha256: String,
        val expiresAtEpochMillis: Long,
    ) {
        fun toProtocolHint() = TransportEndpointHint(webSocketUrl, certificateSha256, expiresAtEpochMillis)
    }

    private companion object {
        const val KEY_HINTS = "direct_transport_hints_v1"
        const val MAX_STORED_HINTS = 8
    }
}

internal fun relayConnectionCandidates(
    configuredRelayWebSocketUrl: String,
    directHints: List<TransportEndpointHint>,
    nowMillis: Long = System.currentTimeMillis(),
): List<RelayConnectionEndpoint> {
    val configured = RelayConnectionEndpoint(
        endpoint = parseRelayEndpoint(configuredRelayWebSocketUrl),
        source = RelayEndpointSource.CONFIGURED,
    )
    val direct = directHints.mapNotNull { hint ->
        validateDirectTransportHint(hint, nowMillis)
    }.distinctBy { it.endpoint.webSocketUrl }
    return (direct + configured).distinctBy { it.endpoint.webSocketUrl }
}

internal fun validateDirectTransportHint(
    hint: TransportEndpointHint,
    nowMillis: Long = System.currentTimeMillis(),
): RelayConnectionEndpoint? {
    if (hint.expiresAtEpochMillis <= nowMillis) return null
    val pin = hint.certificateSha256.lowercase()
    if (!SHA256_REGEX.matches(pin)) return null
    val endpoint = runCatching { parseRelayEndpoint(hint.webSocketUrl) }.getOrNull() ?: return null
    val uri = runCatching { URI(endpoint.webSocketUrl) }.getOrNull() ?: return null
    val host = uri.host ?: return null
    if (!isAllowedDirectLiteralAddress(host)) return null
    return RelayConnectionEndpoint(endpoint, pin, RelayEndpointSource.DIRECT)
}

internal fun isAllowedDirectLiteralAddress(host: String): Boolean {
    if (host.isBlank() || host.any { it.isWhitespace() || it == '/' || it == '@' }) return false
    val raw = host.removePrefix("[").removeSuffix("]")
    val isIpv4Literal = IPV4_LITERAL_REGEX.matches(raw)
    val isIpv6Literal = ':' in raw && IPV6_LITERAL_REGEX.matches(raw)
    if (!isIpv4Literal && !isIpv6Literal) return false
    val address = runCatching { InetAddress.getByName(raw) }.getOrNull() ?: return false
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isMulticastAddress) return false
    // IPv6 link-local endpoints require a scope/zone identifier. Transport hints intentionally
    // do not carry interface scope yet, so accepting fe80::/10 would create unusable routes.
    if (address is Inet6Address && address.isLinkLocalAddress) return false
    if (address.isSiteLocalAddress) return true
    if (address is Inet6Address) {
        val bytes = address.address
        return (bytes[0].toInt() and 0xfe) == 0xfc // fc00::/7 unique-local
    }
    val bytes = address.address.map { it.toInt() and 0xff }
    if (bytes.size != 4) return false
    return bytes[0] == 100 && bytes[1] in 64..127 // RFC 6598 CGNAT, e.g. Tailscale
}

internal class PinnedCertificateTrustManager(
    certificateSha256: String,
) : X509TrustManager {
    private val expected = certificateSha256.lowercase().also {
        require(SHA256_REGEX.matches(it)) { "Pinned certificate SHA-256 is invalid." }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Client certificates are not accepted by this trust manager.")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("TLS server certificate is missing.")
        leaf.checkValidity()
        val actual = MessageDigest.getInstance("SHA-256").digest(leaf.encoded).toHexLower()
        if (!MessageDigest.isEqual(expected.encodeToByteArray(), actual.encodeToByteArray())) {
            throw CertificateException("TLS server certificate pin mismatch.")
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

internal fun certificateSha256(certificate: X509Certificate): String =
    MessageDigest.getInstance("SHA-256").digest(certificate.encoded).toHexLower()

private fun ByteArray.toHexLower(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

private val SHA256_REGEX = Regex("^[0-9a-f]{64}$")
private val IPV4_LITERAL_REGEX = Regex("^(?:\\d{1,3}\\.){3}\\d{1,3}$")
private val IPV6_LITERAL_REGEX = Regex("^[0-9A-Fa-f:]+$")
