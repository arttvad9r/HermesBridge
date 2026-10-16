package io.github.arttvad9r.typeddroid

import io.github.arttvad9r.typeddroid.security.AgentTrustMode
import io.github.arttvad9r.typeddroid.security.ApprovalTicket

enum class ConnectionState {
    DISCONNECTED,
    PAIRING,
    RECONNECTING,
    CONNECTED,
    ERROR,
}

data class DeviceHealthSnapshot(
    val batteryPercent: Int?,
    val availableMemoryBytes: Long,
    val totalMemoryBytes: Long,
    val availableStorageBytes: Long,
    val totalStorageBytes: Long,
)

enum class EmbeddedAdbSetupStatus {
    NOT_PAIRED,
    DISCOVERING_PAIRING,
    PAIRING,
    DISCOVERING_CONNECT,
    BOOTSTRAPPING,
    READY,
    RESTORE_REQUIRED,
    ERROR,
}

data class EmbeddedAdbUiState(
    val status: EmbeddedAdbSetupStatus = EmbeddedAdbSetupStatus.NOT_PAIRED,
    val wasPaired: Boolean = false,
    val message: String? = null,
) {
    val ready: Boolean
        get() = status == EmbeddedAdbSetupStatus.READY

    val busy: Boolean
        get() = status in setOf(
            EmbeddedAdbSetupStatus.DISCOVERING_PAIRING,
            EmbeddedAdbSetupStatus.PAIRING,
            EmbeddedAdbSetupStatus.DISCOVERING_CONNECT,
            EmbeddedAdbSetupStatus.BOOTSTRAPPING,
        )
}

data class BridgeUiState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val pairingCode: String = "",
    val health: DeviceHealthSnapshot? = null,
    val notificationsGranted: Boolean = true,
    val notificationListenerGranted: Boolean = false,
    val fileAccessConfigured: Boolean = false,
    val usageAccessGranted: Boolean = false,
    val setupCompleted: Boolean = false,
    val agentTrustMode: AgentTrustMode = AgentTrustMode.SAFE,
    val embeddedAdb: EmbeddedAdbUiState = EmbeddedAdbUiState(),
    val pendingApprovals: List<ApprovalTicket> = emptyList(),
    val message: String? = null,
)
