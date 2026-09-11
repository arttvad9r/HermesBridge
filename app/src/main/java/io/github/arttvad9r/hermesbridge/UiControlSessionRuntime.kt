package io.github.arttvad9r.hermesbridge

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal const val UI_CONTROL_SESSION_MAX_DURATION_MILLIS = 5 * 60 * 1_000L
internal const val UI_CONTROL_SESSION_REQUIRED_CODE = "ui_control_session_required"
private const val MAX_UI_CONTROL_SESSION_MESSAGE_CHARS = 240

internal enum class UiControlSessionStatus {
    STOPPED,
    ACTIVE,
}

internal data class UiControlSessionState(
    val status: UiControlSessionStatus = UiControlSessionStatus.STOPPED,
    val generation: Long = 0L,
    val startedAtElapsedRealtimeMillis: Long? = null,
    val expiresAtElapsedRealtimeMillis: Long? = null,
    val message: String? = null,
)

/**
 * Opaque authorization lease for one locally activated UI-control session generation.
 *
 * It contains lifecycle metadata only: no coordinates, command arguments, Binder references or
 * other executable data cross this boundary.
 */
internal data class UiControlSessionLease(
    val generation: Long,
    val expiresAtElapsedRealtimeMillis: Long,
)

internal fun newActiveUiControlSessionState(
    nowElapsedRealtimeMillis: Long,
    generation: Long,
    durationMillis: Long = UI_CONTROL_SESSION_MAX_DURATION_MILLIS,
): UiControlSessionState {
    require(nowElapsedRealtimeMillis >= 0L) { "Invalid UI-control session start time." }
    require(generation > 0L) { "Invalid UI-control session generation." }
    require(durationMillis in 1L..UI_CONTROL_SESSION_MAX_DURATION_MILLIS) {
        "Invalid UI-control session duration."
    }
    require(nowElapsedRealtimeMillis <= Long.MAX_VALUE - durationMillis) {
        "UI-control session expiry would overflow."
    }
    return UiControlSessionState(
        status = UiControlSessionStatus.ACTIVE,
        generation = generation,
        startedAtElapsedRealtimeMillis = nowElapsedRealtimeMillis,
        expiresAtElapsedRealtimeMillis = nowElapsedRealtimeMillis + durationMillis,
        message = "UI-control session is active.",
    )
}

internal fun uiControlSessionLease(
    state: UiControlSessionState,
    nowElapsedRealtimeMillis: Long,
): UiControlSessionLease? {
    if (nowElapsedRealtimeMillis < 0L || state.status != UiControlSessionStatus.ACTIVE) return null
    val startedAt = state.startedAtElapsedRealtimeMillis ?: return null
    val expiresAt = state.expiresAtElapsedRealtimeMillis ?: return null
    if (
        state.generation <= 0L ||
        nowElapsedRealtimeMillis < startedAt ||
        nowElapsedRealtimeMillis >= expiresAt
    ) {
        return null
    }
    return UiControlSessionLease(
        generation = state.generation,
        expiresAtElapsedRealtimeMillis = expiresAt,
    )
}

internal fun isUiControlSessionLeaseActive(
    state: UiControlSessionState,
    lease: UiControlSessionLease,
    nowElapsedRealtimeMillis: Long,
): Boolean = uiControlSessionLease(state, nowElapsedRealtimeMillis) == lease

/**
 * Process-local authorization gate for the internal typed UI-control prototype.
 *
 * No remote command can start this session. A later local UI layer will be the only caller of
 * [startLocalSession]. Every privileged UI action must acquire a generation-bound lease here and
 * re-check it immediately before dispatching the Binder call.
 */
internal object UiControlSessionRuntime {
    private val mutableState = MutableStateFlow(UiControlSessionState())
    private val generationCounter = AtomicLong(0L)

    val state: StateFlow<UiControlSessionState> = mutableState.asStateFlow()

    fun startLocalSession(
        nowElapsedRealtimeMillis: Long = SystemClock.elapsedRealtime(),
    ): Boolean = synchronized(this) {
        if (uiControlSessionLease(mutableState.value, nowElapsedRealtimeMillis) != null) {
            return@synchronized false
        }

        // Never carry a cached privileged binding across explicit session generations. Keep the
        // release under the same monitor so a concurrent Stop/Start cannot revoke a newer bind.
        ShizukuUiControlPrototype.release()
        val generation = generationCounter.incrementAndGet()
        mutableState.value = newActiveUiControlSessionState(
            nowElapsedRealtimeMillis = nowElapsedRealtimeMillis,
            generation = generation,
        )
        true
    }

    fun stopLocalSession(message: String? = null) {
        synchronized(this) {
            val generation = generationCounter.incrementAndGet()
            mutableState.value = UiControlSessionState(
                generation = generation,
                message = message?.take(MAX_UI_CONTROL_SESSION_MESSAGE_CHARS),
            )
            ShizukuUiControlPrototype.release()
        }
    }

    fun acquireLease(
        nowElapsedRealtimeMillis: Long = SystemClock.elapsedRealtime(),
    ): UiControlSessionLease? {
        val snapshot = synchronized(this) { mutableState.value }
        val lease = uiControlSessionLease(snapshot, nowElapsedRealtimeMillis)
        if (lease != null) return lease

        if (snapshot.status == UiControlSessionStatus.ACTIVE) {
            expireGeneration(snapshot.generation)
        }
        return null
    }

    fun isLeaseActive(
        lease: UiControlSessionLease,
        nowElapsedRealtimeMillis: Long = SystemClock.elapsedRealtime(),
    ): Boolean {
        val snapshot = synchronized(this) { mutableState.value }
        val active = isUiControlSessionLeaseActive(snapshot, lease, nowElapsedRealtimeMillis)
        if (
            !active &&
            snapshot.status == UiControlSessionStatus.ACTIVE &&
            snapshot.generation == lease.generation
        ) {
            expireGeneration(snapshot.generation)
        }
        return active
    }

    private fun expireGeneration(expectedGeneration: Long) {
        synchronized(this) {
            val current = mutableState.value
            if (
                current.status != UiControlSessionStatus.ACTIVE ||
                current.generation != expectedGeneration
            ) {
                return@synchronized
            }
            val generation = generationCounter.incrementAndGet()
            mutableState.value = UiControlSessionState(
                generation = generation,
                message = "UI-control session expired.",
            )
            ShizukuUiControlPrototype.release()
        }
    }
}
