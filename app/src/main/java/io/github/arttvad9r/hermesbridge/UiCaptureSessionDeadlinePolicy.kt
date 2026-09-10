package io.github.arttvad9r.hermesbridge

internal data class UiCaptureSessionDeadline(
    val startedAtElapsedRealtimeMillis: Long,
    val expiresAtElapsedRealtimeMillis: Long,
)

internal fun newUiCaptureSessionDeadline(
    nowElapsedRealtimeMillis: Long,
    durationMillis: Long = UI_CAPTURE_SESSION_MAX_DURATION_MILLIS,
): UiCaptureSessionDeadline {
    require(nowElapsedRealtimeMillis >= 0L) { "Invalid capture-session start time." }
    require(durationMillis in 1L..UI_CAPTURE_SESSION_MAX_DURATION_MILLIS) {
        "Invalid capture-session duration."
    }
    require(nowElapsedRealtimeMillis <= Long.MAX_VALUE - durationMillis) {
        "Capture-session expiry would overflow."
    }
    return UiCaptureSessionDeadline(
        startedAtElapsedRealtimeMillis = nowElapsedRealtimeMillis,
        expiresAtElapsedRealtimeMillis = nowElapsedRealtimeMillis + durationMillis,
    )
}

internal fun uiCaptureSessionWatchdogDelayMillis(
    deadline: UiCaptureSessionDeadline,
    nowElapsedRealtimeMillis: Long,
): Long? {
    if (nowElapsedRealtimeMillis < 0L) return null
    val remaining = deadline.expiresAtElapsedRealtimeMillis - nowElapsedRealtimeMillis
    if (remaining <= 0L) return null
    return minOf(remaining, UI_CAPTURE_SESSION_WATCHDOG_INTERVAL_MILLIS)
}

internal const val UI_CAPTURE_SESSION_WATCHDOG_INTERVAL_MILLIS = 1_000L
