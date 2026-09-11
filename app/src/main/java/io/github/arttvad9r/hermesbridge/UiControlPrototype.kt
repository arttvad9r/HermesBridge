package io.github.arttvad9r.hermesbridge

/**
 * Internal-only P2 UI-control prototype.
 *
 * These helpers deliberately expose only primary-display tap/swipe primitives. They are not
 * registered in BridgeToolRegistry or the Hermes MCP adapter; a later UI-control session layer
 * must gate any remote exposure behind explicit local consent/revocation.
 */
internal const val MAX_UI_CONTROL_COORDINATE = 32_767
internal const val MIN_UI_SWIPE_DURATION_MILLIS = 1
internal const val MAX_UI_SWIPE_DURATION_MILLIS = 3_000
internal const val UI_INPUT_COMMAND_TIMEOUT_MILLIS = 10_000L
internal const val UI_INPUT_EXECUTABLE = "/system/bin/input"

internal fun isSupportedUiControlCoordinate(value: Int): Boolean =
    value in 0..MAX_UI_CONTROL_COORDINATE

internal fun isSupportedUiSwipeDuration(durationMillis: Int): Boolean =
    durationMillis in MIN_UI_SWIPE_DURATION_MILLIS..MAX_UI_SWIPE_DURATION_MILLIS

internal fun buildUiTapCommand(x: Int, y: Int): Array<String> {
    require(isSupportedUiControlCoordinate(x)) { "Invalid UI x coordinate." }
    require(isSupportedUiControlCoordinate(y)) { "Invalid UI y coordinate." }
    return arrayOf(
        UI_INPUT_EXECUTABLE,
        "touchscreen",
        "tap",
        x.toString(),
        y.toString(),
    )
}

internal fun buildUiSwipeCommand(
    startX: Int,
    startY: Int,
    endX: Int,
    endY: Int,
    durationMillis: Int,
): Array<String> {
    require(isSupportedUiControlCoordinate(startX)) { "Invalid UI start x coordinate." }
    require(isSupportedUiControlCoordinate(startY)) { "Invalid UI start y coordinate." }
    require(isSupportedUiControlCoordinate(endX)) { "Invalid UI end x coordinate." }
    require(isSupportedUiControlCoordinate(endY)) { "Invalid UI end y coordinate." }
    require(isSupportedUiSwipeDuration(durationMillis)) { "Invalid UI swipe duration." }
    return arrayOf(
        UI_INPUT_EXECUTABLE,
        "touchscreen",
        "swipe",
        startX.toString(),
        startY.toString(),
        endX.toString(),
        endY.toString(),
        durationMillis.toString(),
    )
}

internal object UiControlPrototypeProtocol {
    const val KEY_OK = "ok"
    const val KEY_CODE = "code"
    const val KEY_MESSAGE = "message"
}
