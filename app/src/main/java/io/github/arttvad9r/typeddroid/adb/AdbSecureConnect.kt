package io.github.arttvad9r.typeddroid.adb

import java.io.Closeable
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

internal interface AdbPacketDuplex : Closeable {
    fun read(): AdbPacket
    fun write(packet: AdbPacket)
}

/** A connected, authenticated Android 11+ `_adb-tls-connect._tcp` transport. */
internal class AdbSecureConnection(
    private val socket: SSLSocket,
    val session: AdbSession,
    internal val duplex: AdbPacketDuplex,
) : Closeable {
    val banner: AdbDeviceBanner
        get() = checkNotNull(session.deviceBanner)

    fun shellExecutor(): AdbShellExecutor {
        require("shell_v2" in banner.features) { "Connected adbd does not advertise shell_v2." }
        return AdbShellExecutor(
            duplex = duplex,
            multiplexer = session.newStreamMultiplexer(),
        )
    }

    override fun close() {
        session.close()
        runCatching { duplex.close() }
        runCatching { socket.close() }
    }
}

internal class AdbSecureConnectClient(
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 30_000,
) {
    init {
        require(connectTimeoutMillis in 1..60_000)
        require(readTimeoutMillis in 1..300_000)
    }

    fun connect(
        host: String,
        port: Int,
        identity: AndroidAdbIdentity,
    ): AdbSecureConnection {
        require(host.isNotBlank()) { "ADB TLS connect host is empty." }
        require(port in 1..65535) { "ADB TLS connect port is invalid." }

        val rawSocket = Socket()
        try {
            rawSocket.tcpNoDelay = true
            rawSocket.soTimeout = readTimeoutMillis
            rawSocket.connect(InetSocketAddress(host, port), connectTimeoutMillis)

            val session = AdbSession()
            val rawReader = AdbPacketReader(rawSocket.getInputStream(), checksumRequired = true)
            val rawWriter = AdbPacketWriter(rawSocket.getOutputStream(), includeChecksum = true)

            // AOSP host transports always initiate with CNXN. A TLS-enabled adbd replies STLS.
            rawWriter.write(session.start())
            val tlsRequest = rawReader.read()
            val event = session.handleHandshake(tlsRequest)
            require(event is AdbSessionEvent.TlsRequested) {
                "Wireless adbd did not request START_TLS."
            }
            rawWriter.write(
                AdbPacket(
                    command = AdbProtocol.STLS,
                    arg0 = AdbProtocol.TLS_VERSION,
                    arg1 = 0,
                )
            )

            val tlsSocket = upgradeToTls(rawSocket, host, port, identity)
            tlsSocket.soTimeout = readTimeoutMillis

            // adbd authenticates the client certificate, marks the transport online and speaks
            // first by sending CNXN. The host must not send a second CNXN after TLS.
            val postTlsReader = AdbPacketReader(tlsSocket.inputStream, checksumRequired = false)
            val postTlsCnxn = postTlsReader.read()
            val connected = session.handleHandshake(postTlsCnxn)
            require(connected is AdbSessionEvent.Connected) {
                "Wireless adbd did not send CNXN after TLS authentication."
            }

            val checksumRequired = checkNotNull(session.remoteVersion) < AdbProtocol.VERSION_SKIP_CHECKSUM
            val duplex = StreamAdbPacketDuplex(
                socket = tlsSocket,
                reader = AdbPacketReader(tlsSocket.inputStream, checksumRequired = checksumRequired),
                writer = AdbPacketWriter(tlsSocket.outputStream, includeChecksum = checksumRequired),
            )
            return AdbSecureConnection(tlsSocket, session, duplex)
        } catch (error: Throwable) {
            runCatching { rawSocket.close() }
            throw error
        }
    }

    private fun upgradeToTls(
        rawSocket: Socket,
        host: String,
        port: Int,
        identity: AndroidAdbIdentity,
    ): SSLSocket {
        val provider = Conscrypt.newProviderBuilder()
            .setName("TypedDroidAdbConnectConscrypt")
            .build()
        val context = SSLContext.getInstance("TLS", provider)
        context.init(
            arrayOf(AdbConnectIdentityKeyManager(identity)),
            arrayOf(AdbConnectTrustManager),
            SecureRandom(),
        )
        val tlsSocket = context.socketFactory.createSocket(rawSocket, host, port, true) as SSLSocket
        tlsSocket.enabledProtocols = tlsSocket.supportedProtocols
            .filter { it == "TLSv1.3" }
            .toTypedArray()
            .also { require(it.isNotEmpty()) { "Embedded TLS provider does not support TLS 1.3." } }
        tlsSocket.startHandshake()
        return tlsSocket
    }
}

private class StreamAdbPacketDuplex(
    private val socket: SSLSocket,
    private val reader: AdbPacketReader,
    private val writer: AdbPacketWriter,
) : AdbPacketDuplex {
    override fun read(): AdbPacket = reader.read()
    override fun write(packet: AdbPacket) = writer.write(packet)
    override fun close() = socket.close()
}

/**
 * AOSP hosts intentionally accept any adbd server certificate here. Authentication is in the
 * opposite direction: adbd verifies that our TLS certificate contains the RSA key saved at pair.
 */
private object AdbConnectTrustManager : X509ExtendedTrustManager() {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = Unit
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = Unit
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = Unit
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = Unit
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

private class AdbConnectIdentityKeyManager(
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
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
    override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?): String? = null
    override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
        if (alias == ALIAS) arrayOf(identity.certificate) else null
    override fun getPrivateKey(alias: String?): PrivateKey? = if (alias == ALIAS) identity.privateKey else null

    private companion object {
        const val ALIAS = "typeddroid-adb-connect"
    }
}
