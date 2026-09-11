package io.github.arttvad9r.hermesbridge

internal enum class UiControlSessionStopReason {
    EXPIRED_OR_INVALID,
    BRIDGE_DISCONNECTED,
    SHIZUKU_UNAVAILABLE,
    NOTIFICATION_VISIBILITY_LOST,
}

internal fun canStartUiControlSession(
    sessionStatus: UiControlSessionStatus,
    connectionState: ConnectionState,
    shizukuStatus: ShizukuAccessStatus,
    notificationVisible: Boolean,
): Boolean =
    sessionStatus == UiControlSessionStatus.STOPPED &&
        connectionState == ConnectionState.CONNECTED &&
        shizukuStatus == ShizukuAccessStatus.READY &&
        notificationVisible

internal fun uiControlSessionStopReason(
    sessionState: UiControlSessionState,
    nowElapsedRealtimeMillis: Long,
    connectionState: ConnectionState,
    shizukuStatus: ShizukuAccessStatus,
    notificationVisible: Boolean,
): UiControlSessionStopReason? {
    if (sessionState.status != UiControlSessionStatus.ACTIVE) return null
    if (uiControlSessionLease(sessionState, nowElapsedRealtimeMillis) == null) {
        return UiControlSessionStopReason.EXPIRED_OR_INVALID
    }
    if (connectionState != ConnectionState.CONNECTED) {
        return UiControlSessionStopReason.BRIDGE_DISCONNECTED
    }
    if (shizukuStatus != ShizukuAccessStatus.READY) {
        return UiControlSessionStopReason.SHIZUKU_UNAVAILABLE
    }
    if (!notificationVisible) {
        return UiControlSessionStopReason.NOTIFICATION_VISIBILITY_LOST
    }
    return null
}

internal fun UiControlSessionStopReason.displayMessage(): String = when (this) {
    UiControlSessionStopReason.EXPIRED_OR_INVALID ->
        "UI-control session expired or became invalid."
    UiControlSessionStopReason.BRIDGE_DISCONNECTED ->
        "UI-control session stopped because Hermes disconnected."
    UiControlSessionStopReason.SHIZUKU_UNAVAILABLE ->
        "UI-control session stopped because Shizuku is no longer ready."
    UiControlSessionStopReason.NOTIFICATION_VISIBILITY_LOST ->
        "UI-control session stopped because notification visibility was revoked."
}
