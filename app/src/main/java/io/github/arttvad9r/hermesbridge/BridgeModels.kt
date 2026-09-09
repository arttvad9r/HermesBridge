package io.github.arttvad9r.hermesbridge

import io.github.arttvad9r.hermesbridge.security.ApprovalTicket

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
    val fileAccessConfigured: Boolean = false,
    val usageAccessGranted: Boolean = false,
    val setupCompleted: Boolean = false,
    val pendingApprovals: List<ApprovalTicket> = emptyList(),
    val shizuku: ShizukuAccessState = ShizukuAccessState(),
    val message: String? = null,
)
