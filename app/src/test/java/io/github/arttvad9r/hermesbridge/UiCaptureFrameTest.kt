package io.github.arttvad9r.hermesbridge

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiCaptureFrameTest {
    @Test
    fun captureDimensionsPreserveAspectRatioWithinDimensionAndPixelCaps() {
        val tall = boundedUiCaptureDimensions(1440, 3200)
        assertEquals(720, tall.width)
        assertEquals(1600, tall.height)
        assertTrue(tall.width.toLong() * tall.height <= MAX_UI_CAPTURE_PIXELS)

        val square = boundedUiCaptureDimensions(4000, 4000)
        assertEquals(square.width, square.height)
        assertTrue(square.width <= MAX_UI_CAPTURE_DIMENSION)
        assertTrue(square.width.toLong() * square.height <= MAX_UI_CAPTURE_PIXELS)

        assertEquals(UiCaptureDimensions(800, 600), boundedUiCaptureDimensions(800, 600))
    }

    @Test
    fun invalidCaptureDimensionsFailClosed() {
        assertTrue(runCatching { boundedUiCaptureDimensions(0, 100) }.isFailure)
        assertTrue(runCatching { boundedUiCaptureDimensions(100, -1) }.isFailure)
        assertTrue(runCatching { boundedUiCaptureDimensions(100, 100, maxDimension = 0) }.isFailure)
        assertTrue(runCatching { boundedUiCaptureDimensions(100, 100, maxPixels = 0) }.isFailure)
    }

    @Test
    fun paddedRowsAreCopiedWithoutRetainingPadding() {
        val source = ByteBuffer.wrap(
            byteArrayOf(
                1, 2, 3, 4,
                5, 6, 7, 8,
                90, 91, 92, 93,
                9, 10, 11, 12,
                13, 14, 15, 16,
                94, 95, 96, 97,
            )
        )

        val frame = copyUiCaptureRgbaPlane(
            width = 2,
            height = 2,
            pixelStride = 4,
            rowStride = 12,
            buffer = source,
        )

        assertArrayEquals(
            byteArrayOf(
                1, 2, 3, 4,
                5, 6, 7, 8,
                9, 10, 11, 12,
                13, 14, 15, 16,
            ),
            frame.bytes,
        )
    }

    @Test
    fun largerPixelStrideCopiesOnlyRgbaComponents() {
        val source = ByteBuffer.wrap(
            byteArrayOf(
                1, 2, 3, 4, 80, 81, 82, 83,
                5, 6, 7, 8, 84, 85, 86, 87,
            )
        )

        val frame = copyUiCaptureRgbaPlane(
            width = 2,
            height = 1,
            pixelStride = 8,
            rowStride = 16,
            buffer = source,
        )

        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), frame.bytes)
    }

    @Test
    fun malformedStridesAndOversizedFramesFailClosed() {
        val buffer = ByteBuffer.allocate(64)
        assertTrue(
            runCatching {
                copyUiCaptureRgbaPlane(2, 2, pixelStride = 3, rowStride = 8, buffer = buffer)
            }.isFailure
        )
        assertTrue(
            runCatching {
                copyUiCaptureRgbaPlane(2, 2, pixelStride = 4, rowStride = 7, buffer = buffer)
            }.isFailure
        )
        assertTrue(
            runCatching {
                copyUiCaptureRgbaPlane(2, 2, pixelStride = 4, rowStride = 8, buffer = ByteBuffer.allocate(15))
            }.isFailure
        )
        assertTrue(
            runCatching {
                copyUiCaptureRgbaPlane(1600, 1600, pixelStride = 4, rowStride = 6400, buffer = ByteBuffer.allocate(1))
            }.isFailure
        )
    }

    @Test
    fun frameEraseOverwritesRetainedPixelCopy() {
        val frame = UiCaptureRgbaFrame(
            width = 1,
            height = 1,
            bytes = byteArrayOf(1, 2, 3, 4),
        )

        frame.erase()

        assertArrayEquals(byteArrayOf(0, 0, 0, 0), frame.bytes)
    }
}
