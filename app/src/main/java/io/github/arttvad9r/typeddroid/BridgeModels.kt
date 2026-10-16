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

data class BridgeUiState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val pairingCode: String = "",
    val health: DeviceHealthSnapshot? = null,
    val notificationsGranted: Boolean = true,
    val fileAccessConfigured: Boolean = false,
    val usageAccessGranted: Boolean = false,
    val setupCompleted: Boolean = false,
    val agentTrustMode: AgentTrustMode = AgentTrustMode.SAFE,
    val pendingApprovals: List<ApprovalTicket> = emptyList(),
    val shizuku: ShizukuAccessState = ShizukuAccessState(),
    val shizukuWasConfigured: Boolean = false,
    val message: String? = null,
)
