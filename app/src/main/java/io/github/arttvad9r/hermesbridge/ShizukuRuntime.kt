package io.github.arttvad9r.hermesbridge

import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

enum class ShizukuAccessStatus {
    UNAVAILABLE,
    UNSUPPORTED,
    PERMISSION_REQUIRED,
    DENIED,
    READY,
}

data class ShizukuAccessState(
    val status: ShizukuAccessStatus = ShizukuAccessStatus.UNAVAILABLE,
    val serverApiVersion: Int? = null,
    val serverUid: Int? = null,
    val message: String? = null,
)

object ShizukuRuntime {
    private val mutableState = MutableStateFlow(ShizukuAccessState())
    val state: StateFlow<ShizukuAccessState> = mutableState.asStateFlow()

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        refresh()
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        mutableState.value = ShizukuAccessState(
            status = ShizukuAccessStatus.UNAVAILABLE,
            message = "Сервис Shizuku не запущен.",
        )
    }
    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode != REQUEST_CODE) return@OnRequestPermissionResultListener
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            refresh()
        } else {
            mutableState.value = currentBinderMetadata(
                status = ShizukuAccessStatus.DENIED,
                message = "Доступ Hermes Bridge в Shizuku отклонён.",
            )
        }
    }

    init {
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        refresh()
    }

    fun refresh() {
        if (!Shizuku.pingBinder()) {
            mutableState.value = ShizukuAccessState(
                status = ShizukuAccessStatus.UNAVAILABLE,
                message = "Установите и запустите Shizuku для расширенного доступа.",
            )
            return
        }

        runCatching {
            if (Shizuku.isPreV11()) {
                mutableState.value = currentBinderMetadata(
                    status = ShizukuAccessStatus.UNSUPPORTED,
                    message = "Эта версия Shizuku слишком старая.",
                )
                return
            }

            when {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> {
                    mutableState.value = currentBinderMetadata(
                        status = ShizukuAccessStatus.READY,
                        message = null,
                    )
                }
                Shizuku.shouldShowRequestPermissionRationale() -> {
                    mutableState.value = currentBinderMetadata(
                        status = ShizukuAccessStatus.DENIED,
                        message = "Разрешение Shizuku не выдано. Разрешите Hermes Bridge в Shizuku.",
                    )
                }
                else -> {
                    mutableState.value = currentBinderMetadata(
                        status = ShizukuAccessStatus.PERMISSION_REQUIRED,
                        message = "Shizuku запущен, требуется разрешение для Hermes Bridge.",
                    )
                }
            }
        }.onFailure { error ->
            mutableState.value = ShizukuAccessState(
                status = ShizukuAccessStatus.UNAVAILABLE,
                message = error.message ?: "Не удалось получить состояние Shizuku.",
            )
        }
    }

    fun requestPermission() {
        refresh()
        if (mutableState.value.status != ShizukuAccessStatus.PERMISSION_REQUIRED) return

        runCatching {
            Shizuku.requestPermission(REQUEST_CODE)
        }.onFailure { error ->
            mutableState.value = currentBinderMetadata(
                status = ShizukuAccessStatus.UNAVAILABLE,
                message = error.message ?: "Не удалось запросить разрешение Shizuku.",
            )
        }
    }

    private fun currentBinderMetadata(
        status: ShizukuAccessStatus,
        message: String?,
    ): ShizukuAccessState {
        val version = runCatching { Shizuku.getVersion() }
            .getOrNull()
            ?.takeIf { it >= 0 }
        val uid = if (status == ShizukuAccessStatus.READY) {
            runCatching { Shizuku.getUid() }
                .getOrNull()
                ?.takeIf { it >= 0 }
        } else {
            null
        }
        return ShizukuAccessState(
            status = status,
            serverApiVersion = version,
            serverUid = uid,
            message = message,
        )
    }

    private const val REQUEST_CODE = 4001
}
