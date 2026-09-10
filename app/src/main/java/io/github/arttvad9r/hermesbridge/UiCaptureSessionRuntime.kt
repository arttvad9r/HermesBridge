package io.github.arttvad9r.hermesbridge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class UiCaptureSessionStatus {
    STOPPED,
    REQUESTING_CONSENT,
    STARTING,
    ACTIVE,
    DENIED,
    ERROR,
}

internal data class UiCaptureSessionState(
    val status: UiCaptureSessionStatus = UiCaptureSessionStatus.STOPPED,
    val startedAtElapsedRealtimeMillis: Long? = null,
    val expiresAtElapsedRealtimeMillis: Long? = null,
    val message: String? = null,
)

/**
 * Process-local state for the explicitly consented MediaProjection session.
 *
 * This stores lifecycle metadata only. Projection consent Intents, MediaProjection objects and
 * screen pixels never enter this state and are not exposed to the relay/MCP boundary.
 */
internal object UiCaptureSessionRuntime {
    private val mutableState = MutableStateFlow(UiCaptureSessionState())
    val state: StateFlow<UiCaptureSessionState> = mutableState.asStateFlow()

    fun requestingConsent() {
        mutableState.value = UiCaptureSessionState(
            status = UiCaptureSessionStatus.REQUESTING_CONSENT,
            message = "Waiting for Android screen-capture consent.",
        )
    }

    fun denied() {
        mutableState.value = UiCaptureSessionState(
            status = UiCaptureSessionStatus.DENIED,
            message = "Screen-capture consent was not granted.",
        )
    }

    fun starting() {
        mutableState.value = UiCaptureSessionState(
            status = UiCaptureSessionStatus.STARTING,
            message = "Starting the short-lived screen-capture session.",
        )
    }

    fun active(deadline: UiCaptureSessionDeadline) {
        mutableState.value = UiCaptureSessionState(
            status = UiCaptureSessionStatus.ACTIVE,
            startedAtElapsedRealtimeMillis = deadline.startedAtElapsedRealtimeMillis,
            expiresAtElapsedRealtimeMillis = deadline.expiresAtElapsedRealtimeMillis,
            message = "Screen-capture session is active.",
        )
    }

    fun stopped(message: String? = null) {
        mutableState.value = UiCaptureSessionState(
            status = UiCaptureSessionStatus.STOPPED,
            message = message,
        )
    }

    fun error(message: String) {
        mutableState.value = UiCaptureSessionState(
            status = UiCaptureSessionStatus.ERROR,
            message = boundedUiCaptureMessage(message),
        )
    }
}

internal fun canRequestUiCaptureConsent(status: UiCaptureSessionStatus): Boolean =
    when (status) {
        UiCaptureSessionStatus.STOPPED,
        UiCaptureSessionStatus.DENIED,
        UiCaptureSessionStatus.ERROR,
        -> true

        UiCaptureSessionStatus.REQUESTING_CONSENT,
        UiCaptureSessionStatus.STARTING,
        UiCaptureSessionStatus.ACTIVE,
        -> false
    }

internal fun boundedUiCaptureMessage(value: String): String = value
    .asSequence()
    .map { character -> if (character.isISOControl()) ' ' else character }
    .joinToString(separator = "")
    .trim()
    .take(MAX_UI_CAPTURE_MESSAGE_CHARS)

internal const val UI_CAPTURE_SESSION_MAX_DURATION_MILLIS = 5 * 60 * 1000L
internal const val MAX_UI_CAPTURE_MESSAGE_CHARS = 200
