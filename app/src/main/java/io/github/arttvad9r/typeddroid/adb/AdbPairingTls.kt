package io.github.arttvad9r.typeddroid.adb

import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager
import org.conscrypt.Conscrypt

/**
 * TLS 1.3 transport used only for the Android 11+ Wireless Debugging pairing protocol.
 *
 * ADB deliberately accepts the peer's temporary TLS certificate here; peer authentication is
 * provided by the pairing code + TLS exporter through SPAKE2 immediately after the handshake.
 */
internal class AdbPairingTlsClient(
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 10_000,
) {
    init {
        require(connectTimeoutMillis in 1..60_000)
        require(readTimeoutMillis in 1..60_000)
    }

    fun connect(
        host: String,
        port: Int,
        identity: AndroidAdbIdentity,
    ): AdbPairingTlsChannel {
        require(host.isNotBlank()) { "ADB pairing host is empty." }
        require(port in 1..65535) { "ADB pairing port is invalid." }

        val provider = Conscrypt.newProviderBuilder()
            .setName("TypedDroidConscrypt")
            .build()
        val context = SSLContext.getInstance("TLS", provider)
        context.init(
            arrayOf(AdbIdentityKeyManager(identity)),
            arrayOf(AdbPairingTrustManager),
            SecureRandom(),
        )

        val socket = context.socketFactory.createSocket() as SSLSocket
        try {
            socket.enabledProtocols = socket.supportedProtocols
                .filter { it == "TLSv1.3" }
                .toTypedArray()
                .also { require(it.isNotEmpty()) { "Embedded TLS provider does not support TLS 1.3." } }
            socket.soTimeout = readTimeoutMillis
            socket.connect(InetSocketAddress(host, port), connectTimeoutMillis)
            socket.startHandshake()

            // AOSP passes sizeof("adb-label"), so the terminating NUL is part of the exporter label.
            val exported = Conscrypt.exportKeyingMaterial(
                socket,
                ADB_TLS_EXPORTER_LABEL,
                null,
                EXPORTED_KEY_BYTES,
            ) ?: error("ADB pairing TLS exporter returned no key material.")
            require(exported.size == EXPORTED_KEY_BYTES) {
                "ADB pairing TLS exporter returned the wrong key length."
            }
            return AdbPairingTlsChannel(socket, exported)
        } catch (error: Throwable) {
            runCatching { socket.close() }
            throw error
        }
    }

    private companion object {
        const val EXPORTED_KEY_BYTES = 64
        const val ADB_TLS_EXPORTER_LABEL = "adb-label\u0000"
    }
}

internal class AdbPairingTlsChannel(
    private val socket: SSLSocket,
    exportedKeyMaterial: ByteArray,
) : Closeable, AdbPairingTransport {
    private val input: InputStream = socket.inputStream
    private val output: OutputStream = socket.outputStream
    override val exportedKeyMaterial: ByteArray = exportedKeyMaterial.copyOf()

    override fun readFully(size: Int): ByteArray {
        require(size in 1..MAX_PAIRING_IO_BYTES) { "ADB pairing read size is invalid." }
        val result = ByteArray(size)
        var offset = 0
        while (offset < result.size) {
            val read = input.read(result, offset, result.size - offset)
            if (read < 0) throw EOFException("ADB pairing TLS stream ended unexpectedly.")
            if (read == 0) continue
            offset += read
        }
        return result
    }

    override fun writeFully(data: ByteArray) {
        require(data.isNotEmpty() && data.size <= MAX_PAIRING_IO_BYTES) {
            "ADB pairing write size is invalid."
        }
        output.write(data)
        output.flush()
    }

    override fun close() {
        exportedKeyMaterial.fill(0)
        socket.close()
    }

    private companion object {
        const val MAX_PAIRING_IO_BYTES = 32 * 1024
    }
}

private class AdbIdentityKeyManager(
    private val identity: AndroidAdbIdentity,
) : X509ExtendedKeyManager() {
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
        if (keyType.equals("RSA", ignoreCase = true)) arrayOf(ALIAS) else null

    override fun chooseClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        socket: Socket?,
    ): String? = if (keyType?.any { it.equals("RSA", ignoreCase = true) } == true) ALIAS else null

    override fun chooseEngineClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        engine: SSLEngine?,
    ): String? = if (keyType?.any { it.equals("RSA", ignoreCase = true) } == true) ALIAS else null

    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null

    override fun chooseServerAlias(
        keyType: String?,
        issuers: Array<out Principal>?,
        socket: Socket?,
    ): String? = null

    override fun chooseEngineServerAlias(
        keyType: String?,
        issuers: Array<out Principal>?,
        engine: SSLEngine?,
    ): String? = null

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
        if (alias == ALIAS) arrayOf(identity.certificate) else null

    override fun getPrivateKey(alias: String?): PrivateKey? =
        if (alias == ALIAS) identity.privateKey else null

    private companion object {
        const val ALIAS = "typeddroid-adb"
    }
}

private object AdbPairingTrustManager : X509ExtendedTrustManager() {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

    override fun checkClientTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        socket: Socket?,
    ) = Unit

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        socket: Socket?,
    ) = Unit

    override fun checkClientTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        engine: SSLEngine?,
    ) = Unit

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        engine: SSLEngine?,
    ) = Unit

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
