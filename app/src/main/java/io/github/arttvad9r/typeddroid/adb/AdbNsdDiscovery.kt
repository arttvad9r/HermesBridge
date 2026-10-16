package io.github.arttvad9r.typeddroid.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** Android NSD discovery for the two Wireless Debugging DNS-SD service types. */
internal class AndroidAdbNsdDiscovery(context: Context) {
    private val manager = context.applicationContext.getSystemService(NsdManager::class.java)
        ?: error("Android NSD service is unavailable.")

    suspend fun discoverPairingEndpoint(): AdbTcpEndpoint =
        discoverFirst(PAIRING_SERVICE_TYPE, "ADB pairing")

    suspend fun discoverConnectEndpoint(): AdbTcpEndpoint =
        discoverFirst(CONNECT_SERVICE_TYPE, "ADB secure connect")

    private suspend fun discoverFirst(serviceType: String, label: String): AdbTcpEndpoint {
        return withTimeoutOrNull(DISCOVERY_TIMEOUT_MILLIS) {
            awaitFirstResolved(serviceType)
        } ?: throw IllegalStateException(
            "$label service was not discovered. Keep Wireless debugging enabled and try again."
        )
    }

    private suspend fun awaitFirstResolved(serviceType: String): AdbTcpEndpoint =
        suspendCancellableCoroutine { continuation ->
            val completed = AtomicBoolean(false)
            val resolving = AtomicBoolean(false)
            lateinit var listener: NsdManager.DiscoveryListener

            fun stopDiscovery() {
                runCatching { manager.stopServiceDiscovery(listener) }
            }

            fun complete(endpoint: AdbTcpEndpoint) {
                if (!completed.compareAndSet(false, true)) return
                stopDiscovery()
                if (continuation.isActive) continuation.resume(endpoint)
            }

            fun fail(error: Throwable) {
                if (!completed.compareAndSet(false, true)) return
                stopDiscovery()
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) = Unit

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    if (normalizeServiceType(serviceInfo.serviceType) != normalizeServiceType(serviceType)) return
                    if (!resolving.compareAndSet(false, true)) return
                    manager.resolveService(
                        serviceInfo,
                        object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                                resolving.set(false)
                            }

                            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                                val host = serviceInfo.host?.hostAddress
                                val port = serviceInfo.port
                                if (host.isNullOrBlank() || port !in 1..65535) {
                                    resolving.set(false)
                                    return
                                }
                                complete(AdbTcpEndpoint(host, port))
                            }
                        },
                    )
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

                override fun onDiscoveryStopped(serviceType: String) = Unit

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    fail(IllegalStateException("Android NSD could not start discovery (code $errorCode)."))
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            }

            continuation.invokeOnCancellation {
                if (completed.compareAndSet(false, true)) stopDiscovery()
            }
            try {
                manager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (error: Throwable) {
                fail(error)
            }
        }

    private companion object {
        const val PAIRING_SERVICE_TYPE = "_adb-tls-pairing._tcp."
        const val CONNECT_SERVICE_TYPE = "_adb-tls-connect._tcp."
        const val DISCOVERY_TIMEOUT_MILLIS = 15_000L
    }
}

internal fun normalizeServiceType(value: String): String =
    value.trim().trimEnd('.').lowercase()
