package io.github.arttvad9r.hermesbridge

import android.content.Context
import android.os.Build
import io.github.arttvad9r.hermesbridge.protocol.ApprovalRequestPayload
import io.github.arttvad9r.hermesbridge.protocol.AuthChallengePayload
import io.github.arttvad9r.hermesbridge.protocol.AuthResponsePayload
import io.github.arttvad9r.hermesbridge.protocol.BridgeProtocol
import io.github.arttvad9r.hermesbridge.protocol.CommandRequestPayload
import io.github.arttvad9r.hermesbridge.protocol.DeviceRevokeOkPayload
import io.github.arttvad9r.hermesbridge.protocol.ErrorPayload
import io.github.arttvad9r.hermesbridge.protocol.MessageType
import io.github.arttvad9r.hermesbridge.protocol.PairOkPayload
import io.github.arttvad9r.hermesbridge.protocol.PairRequestPayload
import io.github.arttvad9r.hermesbridge.protocol.SessionHelloPayload
import io.github.arttvad9r.hermesbridge.security.ApprovalManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import java.io.IOException
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class RelayAgentTransport(
    context: Context,
    relayWsUrl: String,
    healthRepository: DeviceHealthRepository,
    appsRepository: InstalledAppsRepository = AndroidInstalledAppsRepository(context),
    filesRepository: SafFilesRepository = AndroidSafFilesRepository(context),
    privilegedAppsBackend: PrivilegedAppsBackend = DroidMcpShizukuAppsBackend(),
    apkArtifactRepository: ApkArtifactRepository = RelayApkArtifactRepository(context, relayWsUrl),
    apkPackageInspector: ApkPackageInspector = AndroidApkPackageInspector(context),
    appUsageRepository: AppUsageRepository = AndroidAppUsageRepository(context),
    appPermissionsRepository: AppPermissionsRepository = AndroidAppPermissionsRepository(context),
    batteryDiagnosticsBackend: BatteryDiagnosticsBackend = ShizukuBatteryDiagnosticsBackend(),
    deviceIdentity: DeviceIdentity = AndroidKeystoreDeviceIdentity(),
    pairingRecordStore: PairingRecordStore = PairingStore(context.applicationContext),
) : AgentTransport {
    private val relayWsUrl = parseRelayEndpoint(relayWsUrl).webSocketUrl
    private val identity = deviceIdentity
    private val pairingStore = pairingRecordStore
    private val pairingCredentials = PairingCredentialManager(pairingStore, identity)
    private val coreToolRegistry = BridgeToolRegistry(
        healthRepository = healthRepository,
        appsRepository = appsRepository,
        filesRepository = filesRepository,
        privilegedAppsBackend = privilegedAppsBackend,
        apkArtifactRepository = apkArtifactRepository,
        apkPackageInspector = apkPackageInspector,
        batteryDiagnosticsBackend = batteryDiagnosticsBackend,
        appUsageRepository = appUsageRepository,
    )
    private val commandRouter = BridgeCommandRouter(
        coreRegistry = coreToolRegistry,
        appsRepository = appsRepository,
        appPermissionsRepository = appPermissionsRepository,
        privilegedAppsBackend = privilegedAppsBackend,
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = HttpClient(CIO) {
        install(WebSockets) {
            applyRelayWebSocketPolicy()
        }
    }

    private val mutableConnectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = mutableConnectionState.asStateFlow()

    @Volatile
    private var connectionJob: Job? = null

    @Volatile
    private var activeSession: DefaultClientWebSocketSession? = null

    @Volatile
    private var revokeAck: CompletableDeferred<Result<Unit>>? = null

    override suspend fun pair(code: String): Result<Unit> = startConnection(code)

    override suspend fun resume(): Result<Unit> {
        val validation = pairingCredentials.validateResume()
        if (validation.isFailure) {
            if (pairingStore.deviceId() == null) {
                BridgeApprovalRuntime.clear()
            }
            mutableConnectionState.value = ConnectionState.ERROR
            return validation
        }
        return startConnection(null)
    }

    override suspend fun disconnect(): Result<Unit> = try {
        connectionJob?.cancelAndJoin()
        connectionJob = null
        mutableConnectionState.value = ConnectionState.DISCONNECTED
        Result.success(Unit)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }

    suspend fun revokePairing(): Result<Unit> {
        val deviceId = pairingStore.deviceId() ?: run {
            BridgeApprovalRuntime.clear()
            return Result.success(Unit)
        }
        if (mutableConnectionState.value != ConnectionState.CONNECTED) {
            return Result.failure(
                IllegalStateException("Connect to Hermes before revoking this device pairing.")
            )
        }
        val session = activeSession
            ?: return Result.failure(IllegalStateException("Authenticated relay session is unavailable."))

        val ack = synchronized(this) {
            val existing = revokeAck
            if (existing != null && !existing.isCompleted) {
                return Result.failure(IllegalStateException("Device revocation is already in progress."))
            }
            CompletableDeferred<Result<Unit>>().also { revokeAck = it }
        }

        return try {
            session.sendEnvelope(
                type = MessageType.DEVICE_REVOKE_REQUEST,
                deviceId = deviceId,
            )
            withTimeout(REVOKE_TIMEOUT_MILLIS) { ack.await().getOrThrow() }
            Result.success(Unit)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        } finally {
            synchronized(this) {
                if (revokeAck === ack) revokeAck = null
            }
        }
    }

    fun close() {
        revokeAck?.complete(Result.failure(IllegalStateException("Relay transport closed.")))
        connectionJob?.cancel()
        connectionJob = null
        activeSession = null
        mutableConnectionState.value = ConnectionState.DISCONNECTED
        client.close()
        scope.cancel()
    }

    private suspend fun startConnection(pairingCode: String?): Result<Unit> {
        connectionJob?.cancelAndJoin()
        mutableConnectionState.value = ConnectionState.PAIRING

        val ready = CompletableDeferred<Result<Unit>>()
        connectionJob = scope.launch {
            connectionLoop(pairingCode, ready)
        }

        return try {
            withTimeout(20_000L) { ready.await().getOrThrow() }
            Result.success(Unit)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            mutableConnectionState.value = ConnectionState.ERROR
            Result.failure(error)
        }
    }

    private suspend fun connectionLoop(
        initialPairingCode: String?,
        ready: CompletableDeferred<Result<Unit>>,
    ) {
        var pairingCode = initialPairingCode
        var backoffMillis = 1_000L

        while (currentCoroutineContext().isActive) {
            var sessionFailure: Throwable? = null
            val authenticated = try {
                runSession(pairingCode, ready)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (
                    error is DeviceIdentityUnavailableException &&
                    error.mode == DeviceIdentityFailureMode.REPAIR_REQUIRED
                ) {
                    val resetFailure = pairingCredentials.invalidateForRepair().exceptionOrNull()
                    BridgeApprovalRuntime.clear()
                    val terminalError = if (resetFailure == null) {
                        error
                    } else {
                        DeviceIdentityUnavailableException(
                            message = "Hermes Bridge cleared the stale pairing but could not reset the Android Keystore identity. Restart the app and pair again; if the identity is still unavailable, Android app data must be reset before a new pairing can succeed.",
                            mode = DeviceIdentityFailureMode.REPAIR_REQUIRED,
                            cause = resetFailure,
                        )
                    }
                    mutableConnectionState.value = ConnectionState.ERROR
                    if (!ready.isCompleted) ready.complete(Result.failure(terminalError))
                    return
                }
                sessionFailure = error
                false
            }

            if (!ready.isCompleted) {
                val hasStoredPairing = pairingStore.deviceId() != null
                val hasPendingPairing = pairingStore.hasPendingDeviceId()
                if (!hasStoredPairing) {
                    BridgeApprovalRuntime.clear()
                    mutableConnectionState.value = ConnectionState.ERROR
                    ready.complete(
                        Result.failure(
                            sessionFailure
                                ?: IllegalStateException("Relay connection closed before authentication.")
                        )
                    )
                    return
                }

                if (
                    !shouldRetryInitialAuthentication(
                        hasStoredPairing = hasStoredPairing,
                        pairingCode = pairingCode,
                        failure = sessionFailure,
                        hasPendingPairing = hasPendingPairing,
                    )
                ) {
                    mutableConnectionState.value = ConnectionState.ERROR
                    ready.complete(
                        Result.failure(
                            sessionFailure
                                ?: IllegalStateException("Relay connection closed before authentication.")
                        )
                    )
                    return
                }

                pairingCode = null
                mutableConnectionState.value = ConnectionState.RECONNECTING
                val jitter = Random.nextLong(0L, min(1_000L, backoffMillis))
                delay(backoffMillis + jitter)
                backoffMillis = nextRelayReconnectBackoffMillis(backoffMillis)
                continue
            }

            if (pairingStore.deviceId() == null) {
                BridgeApprovalRuntime.clear()
                mutableConnectionState.value = ConnectionState.DISCONNECTED
                return
            }

            pairingCode = null
            if (authenticated) backoffMillis = 1_000L
            mutableConnectionState.value = ConnectionState.RECONNECTING

            val jitter = Random.nextLong(0L, min(1_000L, backoffMillis))
            delay(backoffMillis + jitter)
            backoffMillis = nextRelayReconnectBackoffMillis(backoffMillis)
        }
    }

    private suspend fun runSession(
        pairingCode: String?,
        ready: CompletableDeferred<Result<Unit>>,
    ): Boolean {
        var authenticated = false
        var pendingPairDeviceId: String? = null

        client.webSocket(relayWsUrl) {
            activeSession = this
            try {
                val existingDeviceId = pairingStore.deviceId()
                if (existingDeviceId == null) {
                    val code = pairingCode
                        ?: error("A pairing code is required for an unpaired device.")
                    val pairPayload = PairRequestPayload(
                        code = code,
                        publicKey = identity.publicKeyBase64(),
                        appVersion = BuildConfig.VERSION_NAME,
                        deviceLabel = "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(80),
                    )
                    sendEnvelope(
                        type = MessageType.PAIR_REQUEST,
                        payload = BridgeProtocol.payload(pairPayload),
                    )
                } else {
                    sendEnvelope(
                        type = MessageType.SESSION_HELLO,
                        deviceId = existingDeviceId,
                        payload = BridgeProtocol.payload(SessionHelloPayload(existingDeviceId)),
                    )
                }

                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val envelope = BridgeProtocol.decode(frame.readText())
                    when (envelope.type) {
                        MessageType.PAIR_OK -> {
                            if (existingDeviceId != null || pendingPairDeviceId != null) {
                                error("Relay returned pairing success in an invalid session state.")
                            }
                            val payload = BridgeProtocol.decodePayload<PairOkPayload>(envelope)
                            if (envelope.deviceId != payload.deviceId) {
                                error("Relay returned inconsistent device identity.")
                            }
                            // Persist the relay-assigned identity before proof-of-possession is sent.
                            // The record remains PENDING until AUTH_OK. If this process dies after the
                            // relay commits trust but before the acknowledgement arrives, the next
                            // service instance can resume with the same ID and promote it to ACTIVE.
                            pairingStore.stageDeviceId(payload.deviceId)
                            pendingPairDeviceId = payload.deviceId
                        }

                        MessageType.AUTH_CHALLENGE -> {
                            val expectedDeviceId = pendingPairDeviceId ?: existingDeviceId
                                ?: error("Authentication challenge arrived before a device identity was established.")
                            val deviceId = envelope.deviceId ?: expectedDeviceId
                            if (deviceId != expectedDeviceId) {
                                error("Authentication challenge targets another device.")
                            }
                            val payload = BridgeProtocol.decodePayload<AuthChallengePayload>(envelope)
                            val signature = identity.sign(
                                BridgeProtocol.authSigningBytes(deviceId, payload.challenge)
                            )
                            sendEnvelope(
                                type = MessageType.AUTH_RESPONSE,
                                deviceId = deviceId,
                                payload = BridgeProtocol.payload(AuthResponsePayload(signature)),
                            )
                        }

                        MessageType.AUTH_OK -> {
                            val expectedDeviceId = pendingPairDeviceId ?: existingDeviceId
                                ?: error("Authentication succeeded without an established device identity.")
                            if (envelope.deviceId != expectedDeviceId) {
                                error("Relay returned authentication success for another device.")
                            }
                            if (pendingPairDeviceId != null || pairingStore.hasPendingDeviceId()) {
                                pairingStore.confirmDeviceId(expectedDeviceId)
                            }
                            pendingPairDeviceId = null
                            authenticated = true
                            mutableConnectionState.value = ConnectionState.CONNECTED
                            if (!ready.isCompleted) ready.complete(Result.success(Unit))
                        }

                        MessageType.COMMAND_REQUEST -> {
                            val deviceId = pairingStore.deviceId()
                            if (!authenticated || deviceId == null || envelope.deviceId != deviceId) {
                                continue
                            }
                            val request = BridgeProtocol.decodePayload<CommandRequestPayload>(envelope)
                            val result = commandRouter.execute(request)
                            sendPendingApprovalNotifications(deviceId)
                            sendEnvelope(
                                type = MessageType.COMMAND_RESULT,
                                deviceId = deviceId,
                                payload = BridgeProtocol.payload(result),
                            )
                        }

                        MessageType.DEVICE_REVOKE_OK -> {
                            val deviceId = pairingStore.deviceId()
                                ?: error("Device revoke acknowledgement arrived without local pairing state.")
                            val payload = BridgeProtocol.decodePayload<DeviceRevokeOkPayload>(envelope)
                            if (envelope.deviceId != deviceId || payload.deviceId != deviceId) {
                                error("Relay returned inconsistent device revoke acknowledgement.")
                            }
                            pairingStore.clear()
                            BridgeApprovalRuntime.clear()
                            mutableConnectionState.value = ConnectionState.DISCONNECTED
                            revokeAck?.complete(Result.success(Unit))
                            close(CloseReason(CloseReason.Codes.NORMAL, "Pairing revoked"))
                            return@webSocket
                        }

                        MessageType.HEARTBEAT_PING -> {
                            sendEnvelope(
                                type = MessageType.HEARTBEAT_PONG,
                                deviceId = pairingStore.deviceId(),
                            )
                        }

                        MessageType.ERROR -> {
                            val payload = BridgeProtocol.decodePayload<ErrorPayload>(envelope)
                            val displayMessage = relayErrorDisplayMessage(payload)
                            revokeAck?.takeIf { !it.isCompleted }?.complete(
                                Result.failure(IllegalStateException(displayMessage))
                            )
                            if (payload.code == "unknown_device") {
                                pairingStore.clear()
                                BridgeApprovalRuntime.clear()
                                mutableConnectionState.value = ConnectionState.ERROR
                            }
                            error(displayMessage)
                        }
                    }
                }
            } finally {
                if (activeSession === this) activeSession = null
                revokeAck?.takeIf { !it.isCompleted }?.complete(
                    Result.failure(IllegalStateException("Relay session closed before revocation completed."))
                )
            }
        }

        return authenticated
    }

    private suspend fun DefaultClientWebSocketSession.sendPendingApprovalNotifications(deviceId: String) {
        while (true) {
            val notification = BridgeApprovalRuntime.takeRemoteNotification() ?: return
            val remainingMillis = notification.expiresAtEpochMillis - System.currentTimeMillis()
            if (remainingMillis <= 0L) continue
            val payload = ApprovalRequestPayload(
                approvalId = notification.id,
                tool = notification.tool,
                risk = notification.risk.name,
                expiresInMillis = remainingMillis.coerceAtMost(ApprovalManager.MAX_TTL_MILLIS),
            )
            sendEnvelope(
                type = MessageType.APPROVAL_REQUEST,
                deviceId = deviceId,
                payload = BridgeProtocol.payload(payload),
            )
        }
    }

    private suspend fun DefaultClientWebSocketSession.sendEnvelope(
        type: String,
        deviceId: String? = null,
        payload: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap()),
    ) {
        send(
            Frame.Text(
                BridgeProtocol.encode(
                    BridgeProtocol.envelope(type, deviceId, payload)
                )
            )
        )
    }

    private companion object {
        const val REVOKE_TIMEOUT_MILLIS = 15_000L
    }
}

internal fun shouldRetryInitialAuthentication(
    hasStoredPairing: Boolean,
    pairingCode: String?,
    failure: Throwable?,
    hasPendingPairing: Boolean = false,
): Boolean =
    hasStoredPairing &&
        (pairingCode == null || hasPendingPairing) &&
        (failure == null || failure is IOException)

internal fun nextRelayReconnectBackoffMillis(currentMillis: Long): Long {
    require(currentMillis > 0L)
    return min(currentMillis * 2L, MAX_RELAY_RECONNECT_BACKOFF_MILLIS)
}

internal const val MAX_RELAY_RECONNECT_BACKOFF_MILLIS = 30_000L
