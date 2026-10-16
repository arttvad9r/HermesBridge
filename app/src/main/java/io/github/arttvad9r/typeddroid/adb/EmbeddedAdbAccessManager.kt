package io.github.arttvad9r.typeddroid.adb

import android.content.Context
import io.github.arttvad9r.typeddroid.EmbeddedPrivilegedClient
import io.github.arttvad9r.typeddroid.EmbeddedPrivilegedStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class AdbTcpEndpoint(
    val host: String,
    val port: Int,
) {
    init {
        require(host.isNotBlank()) { "ADB endpoint host is empty." }
        require(port in 1..65535) { "ADB endpoint port is invalid." }
    }
}

internal data class EmbeddedAdbAccessResult(
    val socketName: String,
    val deviceGuid: String? = null,
)

/** Orchestrates pairing/bootstrap without coupling it to the eventual onboarding UI or mDNS. */
internal class EmbeddedAdbAccessManager(context: Context) {
    private val store = EmbeddedPrivilegedStore(context)
    private val identityStore = AndroidAdbIdentityStore()

    fun helperReady(): Boolean {
        val socketName = store.socketName() ?: return false
        val ready = EmbeddedPrivilegedClient(socketName).ping()
        if (!ready) runCatching(store::clear)
        return ready
    }

    fun wasPaired(): Boolean = store.wasAdbPaired()

    suspend fun pair(
        pairing: AdbTcpEndpoint,
        pairingCode: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val result = AdbPairingClient().pair(
                host = pairing.host,
                port = pairing.port,
                pairingCode = pairingCode,
                identity = identityStore.getOrCreate(),
            )
            store.markAdbPaired()
            Result.success(result.deviceGuid)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    suspend fun pairAndBootstrap(
        pairing: AdbTcpEndpoint,
        connect: AdbTcpEndpoint,
        pairingCode: String,
    ): Result<EmbeddedAdbAccessResult> {
        val deviceGuid = pair(pairing, pairingCode).getOrElse { return Result.failure(it) }
        return restoreAfterReboot(connect).map { it.copy(deviceGuid = deviceGuid) }
    }

    suspend fun restoreAfterReboot(
        connect: AdbTcpEndpoint,
    ): Result<EmbeddedAdbAccessResult> = withContext(Dispatchers.IO) {
        try {
            check(store.wasAdbPaired()) { "TypedDroid has not paired its embedded ADB identity yet." }
            val bootstrap = connectAndBootstrap(connect, identityStore.getOrCreate())
            Result.success(EmbeddedAdbAccessResult(socketName = bootstrap.socketName))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            runCatching(store::clear)
            Result.failure(error)
        }
    }

    fun forgetEmbeddedAdb() {
        store.socketName()?.let { runCatching { EmbeddedPrivilegedClient(it).shutdown() } }
        store.clearAll()
        identityStore.reset()
    }

    private fun connectAndBootstrap(
        endpoint: AdbTcpEndpoint,
        identity: AndroidAdbIdentity,
    ): EmbeddedAdbBootstrapResult = AdbSecureConnectClient()
        .connect(endpoint.host, endpoint.port, identity)
        .use { connection ->
            EmbeddedAdbBootstrap(store).bootstrap(connection)
        }
}
