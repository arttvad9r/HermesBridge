package io.github.arttvad9r.hermesbridge

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * App-side binding for the internal P2 UI-control prototype.
 *
 * Nothing in BridgeCommandRouter or the Hermes MCP adapter calls this object. Every typed action is
 * fail-closed behind a short-lived generation-bound [UiControlSessionRuntime] lease, and local
 * session revocation destroys the dedicated Shizuku UserService binding.
 */
internal object ShizukuUiControlPrototype {
    private val bindMutex = Mutex()
    private val bindingGeneration = AtomicLong(0L)

    @Volatile
    private var cachedService: IUiControlPrototypeService? = null

    @Volatile
    private var activeConnection: ServiceConnection? = null

    private val serviceArgs: Shizuku.UserServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(
                BuildConfig.APPLICATION_ID,
                HermesBridgeUiControlUserService::class.java.name,
            )
        )
            .daemon(false)
            .tag(SERVICE_TAG)
            .version(BuildConfig.VERSION_CODE)
            .processNameSuffix("ui-control")
            .debuggable(BuildConfig.DEBUG)
    }

    fun readiness(): PrivilegedBackendReadiness {
        val localState = ShizukuRuntime.state.value
        if (localState.status != ShizukuAccessStatus.READY) {
            return PrivilegedBackendReadiness(
                ready = false,
                code = "shizuku_unavailable",
                message = localState.message ?: "Shizuku is not ready for UI-control prototyping.",
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

    suspend fun tapPrimaryDisplay(x: Int, y: Int): PrivilegedOperationResult {
        if (!isSupportedUiControlCoordinate(x) || !isSupportedUiControlCoordinate(y)) {
            return PrivilegedOperationResult(
                ok = false,
                code = "invalid_ui_coordinate",
                message = "UI tap coordinates are outside the supported primary-display range.",
            )
        }
        val sessionLease = UiControlSessionRuntime.acquireLease()
            ?: return inactiveSessionResult()
        return invokeOnce(sessionLease) { service ->
            service.tapPrimaryDisplay(x, y).toOperationResult()
        }
    }

    suspend fun swipePrimaryDisplay(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMillis: Int,
    ): PrivilegedOperationResult {
        if (
            !isSupportedUiControlCoordinate(startX) ||
            !isSupportedUiControlCoordinate(startY) ||
            !isSupportedUiControlCoordinate(endX) ||
            !isSupportedUiControlCoordinate(endY)
        ) {
            return PrivilegedOperationResult(
                ok = false,
                code = "invalid_ui_coordinate",
                message = "UI swipe coordinates are outside the supported primary-display range.",
            )
        }
        if (!isSupportedUiSwipeDuration(durationMillis)) {
            return PrivilegedOperationResult(
                ok = false,
                code = "invalid_ui_swipe_duration",
                message = "UI swipe duration is outside the supported range.",
            )
        }
        val sessionLease = UiControlSessionRuntime.acquireLease()
            ?: return inactiveSessionResult()
        return invokeOnce(sessionLease) { service ->
            service.swipePrimaryDisplay(
                startX,
                startY,
                endX,
                endY,
                durationMillis,
            ).toOperationResult()
        }
    }

    /**
     * Revokes the current binding generation before asking Shizuku to remove the service. A late
     * callback from an older bind therefore cannot repopulate [cachedService] after local revoke.
     */
    fun release() {
        bindingGeneration.incrementAndGet()
        cachedService = null
        val connection = activeConnection
        activeConnection = null
        if (connection != null) {
            runCatching { Shizuku.unbindUserService(serviceArgs, connection, true) }
        }
    }

    private suspend fun invokeOnce(
        sessionLease: UiControlSessionLease,
        block: (IUiControlPrototypeService) -> PrivilegedOperationResult,
    ): PrivilegedOperationResult = withContext(Dispatchers.IO) {
        if (!UiControlSessionRuntime.isLeaseActive(sessionLease)) {
            return@withContext inactiveSessionResult()
        }

        val ready = readiness()
        if (!ready.ready) {
            return@withContext PrivilegedOperationResult(
                ok = false,
                code = ready.code,
                message = ready.message,
            )
        }

        try {
            val resolvedService = service()
            // Binding can take seconds. Re-check the exact session generation immediately before
            // dispatch so a local Stop/expiry during bind cannot authorize a late UI action.
            if (!UiControlSessionRuntime.isLeaseActive(sessionLease)) {
                release()
                return@withContext inactiveSessionResult()
            }
            block(resolvedService)
        } catch (error: CancellationException) {
            // A canceled UI-control operation must not leave its privileged UserService alive.
            release()
            throw error
        } catch (error: Throwable) {
            release()
            if (!UiControlSessionRuntime.isLeaseActive(sessionLease)) {
                return@withContext inactiveSessionResult()
            }
            PrivilegedOperationResult(
                ok = false,
                code = "shizuku_ui_control_failed",
                message = buildString {
                    append("Typed UI-control UserService call failed; the action is not retried automatically. ")
                    append(error.message ?: error::class.java.simpleName)
                }.take(MAX_CLIENT_ERROR_CHARS),
            )
        }
    }

    private suspend fun service(): IUiControlPrototypeService {
        cachedService?.takeIf { it.asBinder().isBinderAlive }?.let { return it }

        return bindMutex.withLock {
            cachedService?.takeIf { it.asBinder().isBinderAlive }?.let { return@withLock it }
            release()
            val expectedGeneration = bindingGeneration.get()

            withTimeout(CONNECT_TIMEOUT_MILLIS) {
                suspendCancellableCoroutine { continuation ->
                    val connection = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                            if (binder == null) {
                                if (activeConnection === this) activeConnection = null
                                if (continuation.isActive) {
                                    continuation.resumeWithException(
                                        IllegalStateException("UI-control UserService returned no Binder.")
                                    )
                                }
                                return
                            }

                            if (
                                bindingGeneration.get() != expectedGeneration ||
                                activeConnection !== this
                            ) {
                                runCatching { Shizuku.unbindUserService(serviceArgs, this, true) }
                                if (continuation.isActive) {
                                    continuation.resumeWithException(
                                        IllegalStateException("UI-control UserService binding was revoked.")
                                    )
                                }
                                return
                            }

                            val resolved = IUiControlPrototypeService.Stub.asInterface(binder)
                            cachedService = resolved
                            if (continuation.isActive) continuation.resume(resolved)
                        }

                        override fun onServiceDisconnected(name: ComponentName?) {
                            if (activeConnection === this) {
                                cachedService = null
                                activeConnection = null
                                bindingGeneration.incrementAndGet()
                            }
                            if (continuation.isActive) {
                                continuation.resumeWithException(
                                    IllegalStateException("UI-control UserService disconnected while binding.")
                                )
                            }
                        }
                    }

                    activeConnection = connection
                    try {
                        Shizuku.bindUserService(serviceArgs, connection)
                    } catch (error: Throwable) {
                        if (activeConnection === connection) activeConnection = null
                        bindingGeneration.compareAndSet(expectedGeneration, expectedGeneration + 1)
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }

                    continuation.invokeOnCancellation {
                        if (activeConnection === connection) {
                            cachedService = null
                            activeConnection = null
                            bindingGeneration.incrementAndGet()
                            runCatching {
                                Shizuku.unbindUserService(serviceArgs, connection, true)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun inactiveSessionResult(): PrivilegedOperationResult = PrivilegedOperationResult(
        ok = false,
        code = UI_CONTROL_SESSION_REQUIRED_CODE,
        message = "A short-lived local UI-control session is required for this action.",
    )

    private fun Bundle.toOperationResult(): PrivilegedOperationResult =
        PrivilegedOperationResult(
            ok = getBoolean(UiControlPrototypeProtocol.KEY_OK, false),
            code = getString(UiControlPrototypeProtocol.KEY_CODE),
            message = getString(UiControlPrototypeProtocol.KEY_MESSAGE),
        )

    private const val SERVICE_TAG = "hermes-bridge-ui-control"
    private const val CONNECT_TIMEOUT_MILLIS = 10_000L
    private const val MAX_CLIENT_ERROR_CHARS = 300
}
