package io.github.arttvad9r.typeddroid

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class UiHierarchyResult(
    val ok: Boolean,
    val xml: String? = null,
    val code: String? = null,
    val message: String? = null,
)

data class UiScreenshotResult(
    val ok: Boolean,
    val bytes: ByteArray = ByteArray(0),
    val width: Int? = null,
    val height: Int? = null,
    val mimeType: String? = null,
    val code: String? = null,
    val message: String? = null,
)

interface UiControlBackend {
    fun readiness(): PrivilegedBackendReadiness
    suspend fun hierarchy(): UiHierarchyResult
    suspend fun screenshot(): UiScreenshotResult
    suspend fun tap(x: Int, y: Int): PrivilegedOperationResult
    suspend fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMillis: Int): PrivilegedOperationResult
    suspend fun text(value: String): PrivilegedOperationResult
    suspend fun key(keyCode: Int): PrivilegedOperationResult
    suspend fun openApp(packageName: String): PrivilegedOperationResult
}

object DisabledUiControlBackend : UiControlBackend {
    override fun readiness() = PrivilegedBackendReadiness(
        ready = false,
        code = "embedded_privileged_unavailable",
        message = "UI control backend is not configured.",
    )

    override suspend fun hierarchy() = UiHierarchyResult(false, code = "embedded_privileged_unavailable", message = "UI control backend is not configured.")
    override suspend fun screenshot() = UiScreenshotResult(false, code = "embedded_privileged_unavailable", message = "UI control backend is not configured.")
    override suspend fun tap(x: Int, y: Int) = unavailable()
    override suspend fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMillis: Int) = unavailable()
    override suspend fun text(value: String) = unavailable()
    override suspend fun key(keyCode: Int) = unavailable()
    override suspend fun openApp(packageName: String) = unavailable()

    private fun unavailable() = PrivilegedOperationResult(
        ok = false,
        code = "embedded_privileged_unavailable",
        message = "UI control backend is not configured.",
    )
}

class EmbeddedUiControlBackend(context: Context) : UiControlBackend {
    private val store = EmbeddedPrivilegedStore(context)

    override fun readiness(): PrivilegedBackendReadiness = embeddedHelperReadiness(store)

    override suspend fun hierarchy(): UiHierarchyResult = withClient(
        unavailable = { code, message -> UiHierarchyResult(false, code = code, message = message) },
    ) { client ->
        val result = client.uiHierarchy()
        if (result.exitCode != 0) {
            return@withClient UiHierarchyResult(false, code = "ui_hierarchy_failed", message = "Android could not read the UI hierarchy.")
        }
        if (result.stdoutTruncated) {
            return@withClient UiHierarchyResult(false, code = "ui_hierarchy_too_large", message = "UI hierarchy exceeded the bounded output limit.")
        }
        val xml = try {
            result.stdout.decodeToString(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            return@withClient UiHierarchyResult(false, code = "ui_hierarchy_invalid", message = "Android returned an invalid UI hierarchy encoding.")
        }
        UiHierarchyResult(ok = true, xml = xml)
    }

    override suspend fun screenshot(): UiScreenshotResult = withClient(
        unavailable = { code, message -> UiScreenshotResult(false, code = code, message = message) },
    ) { client ->
        val result = client.uiScreenshot()
        if (result.bytes.isEmpty() || result.bytes.size > MAX_REMOTE_UI_SCREENSHOT_BYTES) {
            return@withClient UiScreenshotResult(false, code = "ui_screenshot_invalid", message = "Screenshot payload is empty or oversized.")
        }
        UiScreenshotResult(
            ok = true,
            bytes = result.bytes,
            width = result.width,
            height = result.height,
            mimeType = result.mimeType,
        )
    }

    override suspend fun tap(x: Int, y: Int): PrivilegedOperationResult = action { it.uiTap(x, y) }

    override suspend fun swipe(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMillis: Int,
    ): PrivilegedOperationResult = action { it.uiSwipe(startX, startY, endX, endY, durationMillis) }

    override suspend fun text(value: String): PrivilegedOperationResult = action { it.uiText(value) }
    override suspend fun key(keyCode: Int): PrivilegedOperationResult = action { it.uiKey(keyCode) }
    override suspend fun openApp(packageName: String): PrivilegedOperationResult = action { it.openApp(packageName) }

    private suspend fun action(block: (EmbeddedPrivilegedClient) -> EmbeddedPrivilegedExecResult): PrivilegedOperationResult =
        withClient(
            unavailable = { code, message -> PrivilegedOperationResult(false, code, message) },
        ) { client ->
            val result = block(client)
            if (result.exitCode == 0 && !result.stdoutTruncated && !result.stderrTruncated) {
                PrivilegedOperationResult(ok = true)
            } else {
                PrivilegedOperationResult(
                    ok = false,
                    code = "ui_control_failed",
                    message = "Android UI command failed.",
                )
            }
        }

    private suspend fun <T> withClient(
        unavailable: (String, String) -> T,
        block: (EmbeddedPrivilegedClient) -> T,
    ): T = withContext(Dispatchers.IO) {
        val endpoint = store.endpoint()
            ?: return@withContext unavailable(
                "embedded_privileged_unavailable",
                "Restore full device access in TypedDroid before using UI control.",
            )
        try {
            block(EmbeddedPrivilegedClient(endpoint))
        } catch (error: CancellationException) {
            throw error
        } catch (error: EmbeddedPrivilegedRemoteException) {
            unavailable(
                if (error.timedOut) "ui_control_timeout" else "ui_control_failed",
                (error.message ?: "Android UI command failed.").take(300),
            )
        } catch (_: Throwable) {
            runCatching(store::clear)
            unavailable(
                "embedded_privileged_unavailable",
                "The embedded privileged helper is unavailable.",
            )
        }
    }

    private companion object {
        const val MAX_REMOTE_UI_SCREENSHOT_BYTES = 128 * 1024
    }
}

private typealias CharacterCodingException = java.nio.charset.CharacterCodingException
