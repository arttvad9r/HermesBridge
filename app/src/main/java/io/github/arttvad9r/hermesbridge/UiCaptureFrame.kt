package io.github.arttvad9r.hermesbridge

import java.nio.ByteBuffer
import kotlin.math.floor
import kotlin.math.sqrt

internal data class UiCaptureDimensions(
    val width: Int,
    val height: Int,
)

/**
 * Process-local RGBA frame used only while a caller is actively handling one consented capture.
 *
 * This type deliberately has no serialization or persistence helpers. Call [erase] as soon as the
 * frame has been consumed so Hermes Bridge does not retain screen pixels between operations.
 */
internal data class UiCaptureRgbaFrame(
    val width: Int,
    val height: Int,
    val bytes: ByteArray,
) {
    init {
        require(width > 0 && height > 0) { "Capture frame dimensions must be positive." }
        require(bytes.size.toLong() == width.toLong() * height.toLong() * UI_CAPTURE_RGBA_BYTES_PER_PIXEL) {
            "Capture frame byte count does not match its dimensions."
        }
        require(bytes.size <= MAX_UI_CAPTURE_FRAME_BYTES) { "Capture frame exceeds the in-memory safety limit." }
    }

    fun erase() {
        bytes.fill(0)
    }
}

/**
 * Downscales the capture surface while preserving aspect ratio and bounding both dimensions and
 * total RGBA memory. Android scales MediaProjection content to the supplied surface.
 */
internal fun boundedUiCaptureDimensions(
    sourceWidth: Int,
    sourceHeight: Int,
    maxDimension: Int = MAX_UI_CAPTURE_DIMENSION,
    maxPixels: Int = MAX_UI_CAPTURE_PIXELS,
): UiCaptureDimensions {
    require(sourceWidth > 0 && sourceHeight > 0) { "Source capture dimensions must be positive." }
    require(maxDimension > 0 && maxPixels > 0) { "Capture limits must be positive." }

    val sourcePixels = sourceWidth.toDouble() * sourceHeight.toDouble()
    val scale = minOf(
        1.0,
        maxDimension.toDouble() / sourceWidth.toDouble(),
        maxDimension.toDouble() / sourceHeight.toDouble(),
        sqrt(maxPixels.toDouble() / sourcePixels),
    )
    val width = maxOf(1, floor(sourceWidth.toDouble() * scale).toInt())
    val height = maxOf(1, floor(sourceHeight.toDouble() * scale).toInt())

    check(width <= maxDimension && height <= maxDimension)
    check(width.toLong() * height.toLong() <= maxPixels.toLong())
    return UiCaptureDimensions(width = width, height = height)
}

/**
 * Copies only visible RGBA bytes from an ImageReader plane, excluding row/pixel padding.
 *
 * Keeping this stride handling isolated makes the privacy/memory boundary unit-testable without
 * retaining Android Image objects. The returned frame must be erased after immediate processing.
 */
internal fun copyUiCaptureRgbaPlane(
    width: Int,
    height: Int,
    pixelStride: Int,
    rowStride: Int,
    buffer: ByteBuffer,
): UiCaptureRgbaFrame {
    require(width > 0 && height > 0) { "Capture dimensions must be positive." }
    require(pixelStride >= UI_CAPTURE_RGBA_BYTES_PER_PIXEL) { "RGBA pixel stride is too small." }
    require(rowStride > 0) { "RGBA row stride must be positive." }

    val outputBytes = width.toLong() * height.toLong() * UI_CAPTURE_RGBA_BYTES_PER_PIXEL
    require(outputBytes <= MAX_UI_CAPTURE_FRAME_BYTES.toLong()) {
        "Capture frame exceeds the in-memory safety limit."
    }

    val visibleRowBytes = (width.toLong() - 1L) * pixelStride.toLong() + UI_CAPTURE_RGBA_BYTES_PER_PIXEL
    require(rowStride.toLong() >= visibleRowBytes) { "RGBA row stride cannot contain the requested width." }

    val requiredSourceBytes = (height.toLong() - 1L) * rowStride.toLong() + visibleRowBytes
    val source = buffer.duplicate()
    require(requiredSourceBytes <= source.remaining().toLong()) { "RGBA plane buffer is shorter than its declared strides." }

    val sourceStart = source.position()
    val output = ByteArray(outputBytes.toInt())
    var outputOffset = 0
    for (row in 0 until height) {
        val rowStart = sourceStart.toLong() + row.toLong() * rowStride.toLong()
        require(rowStart <= Int.MAX_VALUE.toLong()) { "RGBA row offset is out of range." }
        source.position(rowStart.toInt())

        if (pixelStride == UI_CAPTURE_RGBA_BYTES_PER_PIXEL) {
            val packedRowBytes = width * UI_CAPTURE_RGBA_BYTES_PER_PIXEL
            source.get(output, outputOffset, packedRowBytes)
            outputOffset += packedRowBytes
            continue
        }

        for (column in 0 until width) {
            val pixelStart = rowStart + column.toLong() * pixelStride.toLong()
            require(pixelStart <= Int.MAX_VALUE.toLong()) { "RGBA plane offset is out of range." }
            source.position(pixelStart.toInt())
            source.get(output, outputOffset, UI_CAPTURE_RGBA_BYTES_PER_PIXEL)
            outputOffset += UI_CAPTURE_RGBA_BYTES_PER_PIXEL
        }
    }

    return UiCaptureRgbaFrame(width = width, height = height, bytes = output)
}

internal const val UI_CAPTURE_RGBA_BYTES_PER_PIXEL = 4
internal const val MAX_UI_CAPTURE_DIMENSION = 1_600
internal const val MAX_UI_CAPTURE_PIXELS = 1_500_000
internal const val MAX_UI_CAPTURE_FRAME_BYTES = MAX_UI_CAPTURE_PIXELS * UI_CAPTURE_RGBA_BYTES_PER_PIXEL
