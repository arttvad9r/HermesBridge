package io.github.arttvad9r.hermesbridge

import kotlin.math.ceil

internal data class UiCaptureEdgeInsets(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
) {
    init {
        require(left >= 0 && top >= 0 && right >= 0 && bottom >= 0) {
            "Capture redaction insets must be non-negative."
        }
    }
}

/**
 * Unions independent edge masks without adding their sizes together.
 *
 * System bars/cutouts and a visible IME can overlap. Taking the maximum on each edge masks the
 * union while avoiding accidental over-redaction from summing overlapping Android insets.
 */
internal fun combineUiCaptureEdgeInsets(
    first: UiCaptureEdgeInsets,
    second: UiCaptureEdgeInsets,
): UiCaptureEdgeInsets = UiCaptureEdgeInsets(
    left = maxOf(first.left, second.left),
    top = maxOf(first.top, second.top),
    right = maxOf(first.right, second.right),
    bottom = maxOf(first.bottom, second.bottom),
)

/**
 * Builds the redaction mask for a frame from a fresh WindowInsets snapshot.
 *
 * The capture surface is configured from the original maximum-window geometry. If that source
 * geometry changed before the frame arrived (for example because of rotation), the old surface and
 * the fresh insets no longer share a trustworthy coordinate system, so fail closed instead of
 * applying a potentially misplaced mask.
 */
internal fun currentUiCaptureRedactionInsets(
    expectedSourceWidth: Int,
    expectedSourceHeight: Int,
    currentSourceWidth: Int,
    currentSourceHeight: Int,
    targetWidth: Int,
    targetHeight: Int,
    systemInsets: UiCaptureEdgeInsets,
    visibleImeInsets: UiCaptureEdgeInsets,
): UiCaptureEdgeInsets {
    require(
        currentSourceWidth == expectedSourceWidth &&
            currentSourceHeight == expectedSourceHeight
    ) {
        "Capture source geometry changed before frame redaction."
    }
    return scaledUiCaptureEdgeInsets(
        sourceWidth = expectedSourceWidth,
        sourceHeight = expectedSourceHeight,
        targetWidth = targetWidth,
        targetHeight = targetHeight,
        sourceInsets = combineUiCaptureEdgeInsets(systemInsets, visibleImeInsets),
    )
}

/**
 * Scales source-display edge insets into the bounded capture surface.
 *
 * Positive source insets round up so downscaling cannot leave a one-pixel strip of system UI
 * visible at a masked edge. Values are clamped to the target bounds; overlapping masks therefore
 * fail safely by redacting the whole affected row/column rather than exposing pixels.
 */
internal fun scaledUiCaptureEdgeInsets(
    sourceWidth: Int,
    sourceHeight: Int,
    targetWidth: Int,
    targetHeight: Int,
    sourceInsets: UiCaptureEdgeInsets,
): UiCaptureEdgeInsets {
    require(sourceWidth > 0 && sourceHeight > 0) { "Source capture dimensions must be positive." }
    require(targetWidth > 0 && targetHeight > 0) { "Target capture dimensions must be positive." }

    fun scale(value: Int, sourceSize: Int, targetSize: Int): Int {
        if (value == 0) return 0
        return ceil(value.toDouble() * targetSize.toDouble() / sourceSize.toDouble())
            .toInt()
            .coerceIn(0, targetSize)
    }

    return UiCaptureEdgeInsets(
        left = scale(sourceInsets.left, sourceWidth, targetWidth),
        top = scale(sourceInsets.top, sourceHeight, targetHeight),
        right = scale(sourceInsets.right, sourceWidth, targetWidth),
        bottom = scale(sourceInsets.bottom, sourceHeight, targetHeight),
    )
}

/**
 * Overwrites locally derived system-edge regions in-place with opaque black RGBA pixels.
 *
 * This function never creates a second frame-sized buffer. It is deliberately limited to edge
 * masks derived locally from Android WindowInsets; app-content/sensitive-field redaction requires
 * a separate design and is not inferred from pixels here.
 */
internal fun redactUiCaptureFrameEdgesInPlace(
    frame: UiCaptureRgbaFrame,
    insets: UiCaptureEdgeInsets,
) {
    val left = insets.left.coerceAtMost(frame.width)
    val top = insets.top.coerceAtMost(frame.height)
    val right = insets.right.coerceAtMost(frame.width)
    val bottom = insets.bottom.coerceAtMost(frame.height)

    val bottomStart = (frame.height - bottom).coerceAtLeast(top)
    for (row in 0 until frame.height) {
        val maskWholeRow = row < top || row >= bottomStart
        if (maskWholeRow) {
            redactUiCapturePixelRange(frame.bytes, row * frame.width, frame.width)
            continue
        }

        if (left > 0) {
            redactUiCapturePixelRange(frame.bytes, row * frame.width, left)
        }
        if (right > 0) {
            val rightStartColumn = (frame.width - right).coerceAtLeast(left)
            redactUiCapturePixelRange(
                frame.bytes,
                row * frame.width + rightStartColumn,
                frame.width - rightStartColumn,
            )
        }
    }
}

private fun redactUiCapturePixelRange(bytes: ByteArray, firstPixel: Int, pixelCount: Int) {
    var offset = firstPixel * UI_CAPTURE_RGBA_BYTES_PER_PIXEL
    val endOffset = offset + pixelCount * UI_CAPTURE_RGBA_BYTES_PER_PIXEL
    while (offset < endOffset) {
        bytes[offset] = 0
        bytes[offset + 1] = 0
        bytes[offset + 2] = 0
        bytes[offset + 3] = 0xFF.toByte()
        offset += UI_CAPTURE_RGBA_BYTES_PER_PIXEL
    }
}
