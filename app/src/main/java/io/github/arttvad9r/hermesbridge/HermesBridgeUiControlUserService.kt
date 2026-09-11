package io.github.arttvad9r.hermesbridge

import android.os.Bundle
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Experimental, separately-bindable Shizuku UserService for narrow UI actions.
 *
 * The Binder contract accepts only validated numeric tap/swipe parameters for the primary display.
 * It deliberately exposes no executable, shell text, argv array, display selector, key event, or
 * text-input primitive. The standard Hermes tool registry does not route to this service yet.
 */
class HermesBridgeUiControlUserService() : IUiControlPrototypeService.Stub() {
    override fun destroy() {
        exitProcess(0)
    }

    override fun tapPrimaryDisplay(x: Int, y: Int): Bundle {
        if (!isSupportedUiControlCoordinate(x) || !isSupportedUiControlCoordinate(y)) {
            return failure(
                code = "invalid_ui_coordinate",
                message = "UI tap coordinates are outside the supported primary-display range.",
            )
        }
        return executeInputCommand(buildUiTapCommand(x, y))
    }

    override fun swipePrimaryDisplay(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMillis: Int,
    ): Bundle {
        if (
            !isSupportedUiControlCoordinate(startX) ||
            !isSupportedUiControlCoordinate(startY) ||
            !isSupportedUiControlCoordinate(endX) ||
            !isSupportedUiControlCoordinate(endY)
        ) {
            return failure(
                code = "invalid_ui_coordinate",
                message = "UI swipe coordinates are outside the supported primary-display range.",
            )
        }
        if (!isSupportedUiSwipeDuration(durationMillis)) {
            return failure(
                code = "invalid_ui_swipe_duration",
                message = "UI swipe duration is outside the supported range.",
            )
        }
        return executeInputCommand(
            buildUiSwipeCommand(
                startX = startX,
                startY = startY,
                endX = endX,
                endY = endY,
                durationMillis = durationMillis,
            )
        )
    }

    private fun executeInputCommand(argv: Array<String>): Bundle {
        val process = try {
            ProcessBuilder(*argv)
                .redirectErrorStream(true)
                .start()
        } catch (error: Throwable) {
            return failure(
                code = "ui_input_unavailable",
                message = safeErrorMessage(error),
            )
        }

        val executor = Executors.newSingleThreadExecutor()
        return try {
            runCatching { process.outputStream.close() }
            val outputFuture = executor.submit<String> {
                process.inputStream.use { readBoundedText(it, MAX_UI_INPUT_OUTPUT_BYTES) }
            }

            val finished = process.waitFor(UI_INPUT_COMMAND_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            if (!finished) {
                runCatching { process.destroyForcibly() }
                outputFuture.cancel(true)
                failure(
                    code = "ui_input_timeout",
                    message = "Android input command did not finish within ${UI_INPUT_COMMAND_TIMEOUT_MILLIS / 1000} seconds.",
                )
            } else {
                val output = runCatching { outputFuture.get() }.getOrDefault("")
                if (process.exitValue() == 0) {
                    success()
                } else {
                    failure(
                        code = "ui_input_failed",
                        message = output.lineSequence()
                            .map(String::trim)
                            .firstOrNull(String::isNotEmpty)
                            ?: "Android rejected the typed UI input action.",
                    )
                }
            }
        } catch (error: Throwable) {
            runCatching { process.destroyForcibly() }
            failure(
                code = "ui_input_failed",
                message = safeErrorMessage(error),
            )
        } finally {
            executor.shutdownNow()
        }
    }

    private fun readBoundedText(input: InputStream, limitBytes: Int): String {
        val output = ByteArrayOutputStream(minOf(limitBytes, 4 * 1024))
        val buffer = ByteArray(4 * 1024)
        var retained = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (retained < limitBytes) {
                val accepted = minOf(read, limitBytes - retained)
                output.write(buffer, 0, accepted)
                retained += accepted
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun safeErrorMessage(error: Throwable): String {
        val source = error.cause ?: error
        return safeMessage(source.message ?: source::class.java.simpleName)
    }

    private fun safeMessage(value: String): String = value
        .asSequence()
        .map { character -> if (character.isISOControl()) ' ' else character }
        .joinToString(separator = "")
        .trim()
        .take(MAX_UI_SERVICE_MESSAGE_CHARS)

    private fun success() = Bundle().apply {
        putBoolean(UiControlPrototypeProtocol.KEY_OK, true)
    }

    private fun failure(code: String, message: String) = Bundle().apply {
        putBoolean(UiControlPrototypeProtocol.KEY_OK, false)
        putString(UiControlPrototypeProtocol.KEY_CODE, code)
        putString(UiControlPrototypeProtocol.KEY_MESSAGE, safeMessage(message))
    }

    private companion object {
        const val MAX_UI_INPUT_OUTPUT_BYTES = 4 * 1024
        const val MAX_UI_SERVICE_MESSAGE_CHARS = 200
    }
}
