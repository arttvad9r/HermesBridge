package io.github.arttvad9r.typeddroid

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import io.github.arttvad9r.typeddroid.security.AgentTrustMode
import io.github.arttvad9r.typeddroid.adb.AndroidAdbNsdDiscovery
import io.github.arttvad9r.typeddroid.adb.EmbeddedAdbAccessManager
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class BridgeViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val healthRepository: DeviceHealthRepository =
        AndroidDeviceHealthRepository(application)
    private val appUsageRepository: AppUsageRepository =
        AndroidAppUsageRepository(application)
    private val pairingStore = PairingStore(application)
    private val treeStore = SafTreeStore(application)
    private val setupStore = SetupStore(application)
    private val embeddedAdbAccess = EmbeddedAdbAccessManager(application)
    private val embeddedAdbDiscovery = AndroidAdbNsdDiscovery(application)

    private val _state = MutableStateFlow(
        BridgeUiState(
            health = healthRepository.snapshot(),
            notificationsGranted = BridgeNotificationPermission.isGranted(application),
            fileAccessConfigured = treeStore.treeUri() != null,
            usageAccessGranted = appUsageRepository.hasAccess(),
            setupCompleted = setupStore.isCompleted(),
            agentTrustMode = pairingStore.trustMode(),
            embeddedAdb = currentEmbeddedAdbState(),
        )
    )
    val state: StateFlow<BridgeUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            BridgeRuntime.state.collectLatest { runtime ->
                val hasStoredPairing = pairingStore.deviceId() != null
                if (!hasStoredPairing && _state.value.setupCompleted) {
                    setupStore.reset()
                }
                _state.update { current ->
                    current.copy(
                        connectionState = runtime.connectionState,
                        pairingCode = if (runtime.connectionState == ConnectionState.CONNECTED) {
                            ""
                        } else {
                            current.pairingCode
                        },
                        message = runtime.message,
                        setupCompleted = reconciledSetupCompletion(
                            currentSetupCompleted = current.setupCompleted,
                            hasStoredPairing = hasStoredPairing,
                        ),
                        agentTrustMode = pairingStore.trustMode(),
                    )
                }
            }
        }

        viewModelScope.launch {
            BridgeApprovalRuntime.pendingTickets.collectLatest { tickets ->
                _state.update { it.copy(pendingApprovals = tickets) }
            }
        }

        BridgeApprovalRuntime.refresh()

        if (pairingStore.deviceId() != null) {
            runCatching { BridgeForegroundService.connect(app) }
                .onFailure { error ->
                    BridgeRuntime.update(
                        ConnectionState.ERROR,
                        error.message ?: "Не удалось запустить фоновое соединение.",
                    )
                }
        }
    }

    fun updatePairingCode(value: String) {
        _state.update {
            it.copy(
                pairingCode = value.uppercase().take(9),
                message = null,
            )
        }
    }

    fun refreshHealth() {
        _state.update { it.copy(health = healthRepository.snapshot()) }
    }

    fun refreshNotificationPermission() {
        _state.update {
            it.copy(notificationsGranted = BridgeNotificationPermission.isGranted(app))
        }
    }

    fun refreshUsageAccess() {
        _state.update { it.copy(usageAccessGranted = appUsageRepository.hasAccess()) }
    }

    fun approveAction(approvalId: String) {
        BridgeApprovalRuntime.approve(approvalId)
    }

    fun denyAction(approvalId: String) {
        BridgeApprovalRuntime.deny(approvalId)
    }

    fun setAgentTrustMode(mode: AgentTrustMode) {
        if (pairingStore.deviceId() == null) {
            _state.update { it.copy(message = "Сначала подключите Bridge Host.") }
            return
        }
        runCatching { pairingStore.saveTrustMode(mode) }
            .onSuccess {
                _state.update { it.copy(agentTrustMode = mode, message = null) }
            }
            .onFailure { error ->
                _state.update {
                    it.copy(message = error.message ?: "Не удалось сохранить уровень доверия агенту.")
                }
            }
    }

    fun pairEmbeddedAdb(pairingCode: String) {
        val code = pairingCode.filter(Char::isDigit)
        if (code.length != 6) {
            _state.update {
                it.copy(embeddedAdb = it.embeddedAdb.copy(
                    status = EmbeddedAdbSetupStatus.ERROR,
                    message = "Код Wireless Debugging должен содержать 6 цифр.",
                ))
            }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                updateEmbeddedAdb(EmbeddedAdbSetupStatus.DISCOVERING_PAIRING, "Ищем Android pairing service…")
                val pairingEndpoint = embeddedAdbDiscovery.discoverPairingEndpoint()
                updateEmbeddedAdb(EmbeddedAdbSetupStatus.PAIRING, "Подтверждаем ADB-ключ TypedDroid…")
                embeddedAdbAccess.pair(pairingEndpoint, code).getOrThrow()

                updateEmbeddedAdb(EmbeddedAdbSetupStatus.DISCOVERING_CONNECT, "Pairing готов. Ищем защищённый ADB endpoint…")
                val connectEndpoint = embeddedAdbDiscovery.discoverConnectEndpoint()
                updateEmbeddedAdb(EmbeddedAdbSetupStatus.BOOTSTRAPPING, "Запускаем встроенный shell-UID helper…")
                embeddedAdbAccess.restoreAfterReboot(connectEndpoint).getOrThrow()
                _state.update { it.copy(embeddedAdb = currentEmbeddedAdbState("Встроенный полный доступ готов.")) }
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                val paired = embeddedAdbAccess.wasPaired()
                _state.update {
                    it.copy(embeddedAdb = EmbeddedAdbUiState(
                        status = EmbeddedAdbSetupStatus.ERROR,
                        wasPaired = paired,
                        message = (error.message ?: "Не удалось настроить встроенный ADB.").take(300),
                    ))
                }
            }
        }
    }

    fun restoreEmbeddedAdb() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                updateEmbeddedAdb(EmbeddedAdbSetupStatus.DISCOVERING_CONNECT, "Ищем Android Wireless Debugging…")
                val endpoint = embeddedAdbDiscovery.discoverConnectEndpoint()
                updateEmbeddedAdb(EmbeddedAdbSetupStatus.BOOTSTRAPPING, "Восстанавливаем встроенный helper…")
                embeddedAdbAccess.restoreAfterReboot(endpoint).getOrThrow()
                _state.update { it.copy(embeddedAdb = currentEmbeddedAdbState("Расширенный доступ восстановлен.")) }
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                _state.update {
                    it.copy(embeddedAdb = EmbeddedAdbUiState(
                        status = EmbeddedAdbSetupStatus.ERROR,
                        wasPaired = embeddedAdbAccess.wasPaired(),
                        message = (error.message ?: "Не удалось восстановить встроенный ADB.").take(300),
                    ))
                }
            }
        }
    }

    fun refreshEmbeddedAdb() {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(embeddedAdb = currentEmbeddedAdbState()) }
        }
    }

    fun forgetEmbeddedAdb() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { embeddedAdbAccess.forgetEmbeddedAdb() }
            _state.update { it.copy(embeddedAdb = currentEmbeddedAdbState("Встроенный ADB сброшен.")) }
        }
    }

    private fun updateEmbeddedAdb(status: EmbeddedAdbSetupStatus, message: String?) {
        _state.update {
            it.copy(embeddedAdb = EmbeddedAdbUiState(
                status = status,
                wasPaired = embeddedAdbAccess.wasPaired(),
                message = message,
            ))
        }
    }

    private fun currentEmbeddedAdbState(message: String? = null): EmbeddedAdbUiState {
        val paired = embeddedAdbAccess.wasPaired()
        val ready = runCatching { embeddedAdbAccess.helperReady() }.getOrDefault(false)
        return EmbeddedAdbUiState(
            status = when {
                ready -> EmbeddedAdbSetupStatus.READY
                paired -> EmbeddedAdbSetupStatus.RESTORE_REQUIRED
                else -> EmbeddedAdbSetupStatus.NOT_PAIRED
            },
            wasPaired = paired,
            message = message,
        )
    }

    fun grantFileTree(uri: Uri) {
        val readFlag = Intent.FLAG_GRANT_READ_URI_PERMISSION
        runCatching {
            app.contentResolver.takePersistableUriPermission(uri, readFlag)
            val previous = treeStore.treeUri()
            treeStore.save(uri)
            if (previous != null && previous != uri) {
                runCatching {
                    app.contentResolver.releasePersistableUriPermission(previous, readFlag)
                }
            }
        }.onSuccess {
            _state.update {
                it.copy(
                    fileAccessConfigured = true,
                    message = null,
                )
            }
        }.onFailure { error ->
            _state.update {
                it.copy(
                    fileAccessConfigured = treeStore.treeUri() != null,
                    message = error.message ?: "Не удалось сохранить доступ к выбранной папке.",
                )
            }
        }
    }

    fun revokeFileAccess() {
        val current = treeStore.treeUri()
        if (current != null) {
            runCatching {
                app.contentResolver.releasePersistableUriPermission(
                    current,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        treeStore.clear()
        _state.update {
            it.copy(
                fileAccessConfigured = false,
                message = null,
            )
        }
    }

    fun pair() {
        val code = PairingCodeValidator.normalize(_state.value.pairingCode)
        if (!PairingCodeValidator.isValid(code)) {
            _state.update { it.copy(message = "Код должен иметь формат XXXX-XXXX.") }
            return
        }

        BridgeRuntime.update(ConnectionState.PAIRING)
        runCatching { BridgeForegroundService.pair(app, code) }
            .onFailure { error ->
                BridgeRuntime.update(
                    ConnectionState.ERROR,
                    error.message ?: "Не удалось запустить привязку устройства.",
                )
            }
    }

    fun revokePairing() {
        if (_state.value.connectionState != ConnectionState.CONNECTED) {
            _state.update { it.copy(message = "Для безопасной отвязки сначала восстановите соединение с Bridge Host.") }
            return
        }
        runCatching { BridgeForegroundService.revokePairing(app) }
            .onFailure { error ->
                BridgeRuntime.update(
                    ConnectionState.ERROR,
                    error.message ?: "Не удалось запустить отзыв привязки.",
                )
            }
    }

    fun finishSetup() {
        if (_state.value.connectionState != ConnectionState.CONNECTED) {
            _state.update { it.copy(message = "Сначала подключите Bridge Host.") }
            return
        }
        setupStore.markCompleted()
        _state.update { it.copy(setupCompleted = true, message = null) }
    }

    fun restartSetup() {
        setupStore.reset()
        _state.update { it.copy(setupCompleted = false, message = null) }
    }
}

internal fun reconciledSetupCompletion(
    currentSetupCompleted: Boolean,
    hasStoredPairing: Boolean,
): Boolean = currentSetupCompleted && hasStoredPairing
