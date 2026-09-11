package io.github.arttvad9r.hermesbridge

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object ShizukuPrivilegedUserServiceClient {
    private val bindMutex = Mutex()

    @Volatile
    private var cachedService: IPrivilegedBridgeService? = null

    @Volatile
    private var activeConnection: ServiceConnection? = null

    private val serviceArgs: Shizuku.UserServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(
                BuildConfig.APPLICATION_ID,
                HermesBridgeUserService::class.java.name,
            )
        )
            .daemon(false)
            .tag(SERVICE_TAG)
            .version(BuildConfig.VERSION_CODE)
            .processNameSuffix("privileged")
            .debuggable(BuildConfig.DEBUG)
    }

    fun readiness(): PrivilegedBackendReadiness {
        val localState = ShizukuRuntime.state.value
        if (localState.status != ShizukuAccessStatus.READY) {
            return PrivilegedBackendReadiness(
                ready = false,
                code = "shizuku_unavailable",
                message = localState.message ?: "Shizuku is not ready for privileged operations.",
            )
        }

        val binderReady = runCatching {
            Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        return if (binderReady) {
            PrivilegedBackendReadiness(ready = true)
        } else {
            PrivilegedBackendReadiness(
                ready = false,
                code = "shizuku_unavailable",
                message = "Shizuku binder or permission is no longer available.",
            )
        }
    }

    suspend fun install(
        artifact: VerifiedApkArtifact,
        replace: Boolean,
    ): PrivilegedOperationResult = withContext(Dispatchers.IO) {
        if (!artifact.file.isFile || artifact.sizeBytes !in 1..MAX_APK_ARTIFACT_BYTES) {
            return@withContext PrivilegedOperationResult(
                ok = false,
                code = "invalid_apk_artifact",
                message = "Verified APK file is missing or has an invalid size.",
            )
        }
        if (artifact.file.length() != artifact.sizeBytes) {
            return@withContext PrivilegedOperationResult(
                ok = false,
                code = "invalid_apk_artifact",
                message = "Verified APK size changed before installation.",
            )
        }

        try {
            val service = service()
            ParcelFileDescriptor.open(artifact.file, ParcelFileDescriptor.MODE_READ_ONLY).use { apkFd ->
                service.installApk(apkFd, artifact.sizeBytes, replace).toOperationResult()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            operationFailure(error)
        }
    }

    suspend fun uninstall(
        packageName: String,
        keepData: Boolean,
    ): PrivilegedOperationResult = invokeOnce {
        it.uninstallApp(packageName, keepData).toOperationResult()
    }

    suspend fun forceStop(packageName: String): PrivilegedOperationResult = invokeOnce {
        it.forceStopApp(packageName).toOperationResult()
    }

    suspend fun revokePermission(
        packageName: String,
        permissionName: String,
        userId: Int,
    ): PrivilegedOperationResult = invokeOnce {
        it.revokePermission(packageName, permissionName, userId).toOperationResult()
    }

    suspend fun readBatteryStats(): BatteryStatsTransportResult = withContext(Dispatchers.IO) {
        val readiness = readiness()
        if (!readiness.ready) {
            return@withContext BatteryStatsTransportResult(
                ok = false,
                code = readiness.code,
                message = readiness.message,
            )
        }

        try {
            val pipe = ParcelFileDescriptor.createPipe()
            val readSide = pipe[0]
            val writeSide = pipe[1]
            coroutineScope {
                val stdoutDeferred = async(Dispatchers.IO) {
                    ParcelFileDescriptor.AutoCloseInputStream(readSide).use { input ->
                        readBoundedBatteryStats(input)
                    }
                }
                val operation = writeSide.use { output ->
                    service().writeBatteryStats(output).toOperationResult()
                }
                val stdout = stdoutDeferred.await()

                if (!operation.ok) {
                    BatteryStatsTransportResult(
                        ok = false,
                        code = operation.code,
                        message = operation.message,
                    )
                } else {
                    BatteryStatsTransportResult(
                        ok = true,
                        text = stdout.text,
                        truncated = stdout.truncated,
                    )
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            cachedService = null
            BatteryStatsTransportResult(
                ok = false,
                code = "shizuku_user_service_failed",
                message = buildString {
                    append("Battery diagnostics UserService call failed. ")
                    append(error.message ?: error::class.java.simpleName)
                }.take(300),
            )
        }
    }

    private suspend fun invokeOnce(
        block: (IPrivilegedBridgeService) -> PrivilegedOperationResult,
    ): PrivilegedOperationResult = withContext(Dispatchers.IO) {
        try {
            block(service())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            operationFailure(error)
        }
    }

    private suspend fun service(): IPrivilegedBridgeService {
        cachedService?.takeIf { it.asBinder().isBinderAlive }?.let { return it }

        return bindMutex.withLock {
            cachedService?.takeIf { it.asBinder().isBinderAlive }?.let { return@withLock it }
            clearDeadConnection()

            withTimeout(CONNECT_TIMEOUT_MILLIS) {
                suspendCancellableCoroutine { continuation ->
                    val connection = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                            if (binder == null) {
                                if (continuation.isActive) {
                                    continuation.resumeWithException(
                                        IllegalStateException("Shizuku UserService returned no Binder.")
                                    )
                                }
                                return
                            }
                            val resolved = IPrivilegedBridgeService.Stub.asInterface(binder)
                            cachedService = resolved
                            activeConnection = this
                            if (continuation.isActive) continuation.resume(resolved)
                        }

                        override fun onServiceDisconnected(name: ComponentName?) {
                            if (activeConnection === this) {
                                cachedService = null
                                activeConnection = null
                            }
                            if (continuation.isActive) {
                                continuation.resumeWithException(
                                    IllegalStateException("Shizuku UserService disconnected while binding.")
                                )
                            }
                        }
                    }

                    activeConnection = connection
                    try {
                        Shizuku.bindUserService(serviceArgs, connection)
                    } catch (error: Throwable) {
                        if (activeConnection === connection) activeConnection = null
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }

                    continuation.invokeOnCancellation {
                        if (activeConnection === connection && cachedService == null) {
                            activeConnection = null
                            runCatching {
                                Shizuku.unbindUserService(serviceArgs, connection, false)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun clearDeadConnection() {
        cachedService = null
        val connection = activeConnection ?: return
        activeConnection = null
        runCatching { Shizuku.unbindUserService(serviceArgs, connection, false) }
    }

    private fun android.os.Bundle.toOperationResult(): PrivilegedOperationResult =
        PrivilegedOperationResult(
            ok = getBoolean(PrivilegedUserServiceProtocol.KEY_OK, false),
            code = getString(PrivilegedUserServiceProtocol.KEY_CODE),
            message = getString(PrivilegedUserServiceProtocol.KEY_MESSAGE),
        )

    private fun operationFailure(error: Throwable): PrivilegedOperationResult {
        cachedService = null
        return PrivilegedOperationResult(
            ok = false,
            code = "shizuku_user_service_failed",
            message = buildString {
                append("Privileged UserService call failed; the operation is not retried automatically because its outcome may be unknown. ")
                append(error.message ?: error::class.java.simpleName)
            }.take(300),
        )
    }

    private const val SERVICE_TAG = "hermes-bridge-privileged"
    private const val CONNECT_TIMEOUT_MILLIS = 10_000L
}

internal data class BatteryStatsTransportResult(
    val ok: Boolean,
    val text: String? = null,
    val truncated: Boolean = false,
    val code: String? = null,
    val message: String? = null,
)