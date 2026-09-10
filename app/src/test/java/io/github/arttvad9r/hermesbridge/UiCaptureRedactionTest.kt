package io.github.arttvad9r.hermesbridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiCaptureRedactionTest {
    @Test
    fun sourceInsetsScaleConservativelyIntoCaptureSurface() {
        assertEquals(
            UiCaptureEdgeInsets(left = 5, top = 13, right = 3, bottom = 25),
            scaledUiCaptureEdgeInsets(
                sourceWidth = 1080,
                sourceHeight = 2400,
                targetWidth = 540,
                targetHeight = 1200,
                sourceInsets = UiCaptureEdgeInsets(left = 10, top = 25, right = 6, bottom = 49),
            ),
        )

        // Positive source insets never disappear when downscaled.
        assertEquals(
            UiCaptureEdgeInsets(left = 1, top = 1, right = 1, bottom = 1),
            scaledUiCaptureEdgeInsets(
                sourceWidth = 4000,
                sourceHeight = 4000,
                targetWidth = 100,
                targetHeight = 100,
                sourceInsets = UiCaptureEdgeInsets(left = 1, top = 1, right = 1, bottom = 1),
            ),
        )
    }

    @Test
    fun edgeRedactionOverwritesOnlyRequestedPixelsWithOpaqueBlack() {
        val original = ByteArray(4 * 4 * UI_CAPTURE_RGBA_BYTES_PER_PIXEL) { index -> (index + 1).toByte() }
        val frame = UiCaptureRgbaFrame(width = 4, height = 4, bytes = original.copyOf())

        redactUiCaptureFrameEdgesInPlace(
            frame,
            UiCaptureEdgeInsets(left = 1, top = 1, right = 1, bottom = 1),
        )

        for (row in 0 until 4) {
            for (column in 0 until 4) {
                val offset = (row * 4 + column) * UI_CAPTURE_RGBA_BYTES_PER_PIXEL
                val masked = row == 0 || row == 3 || column == 0 || column == 3
                if (masked) {
                    assertArrayEquals(
                        byteArrayOf(0, 0, 0, 0xFF.toByte()),
                        frame.bytes.copyOfRange(offset, offset + UI_CAPTURE_RGBA_BYTES_PER_PIXEL),
                    )
                } else {
                    assertArrayEquals(
                        original.copyOfRange(offset, offset + UI_CAPTURE_RGBA_BYTES_PER_PIXEL),
                        frame.bytes.copyOfRange(offset, offset + UI_CAPTURE_RGBA_BYTES_PER_PIXEL),
                    )
                }
            }
        }
    }

    @Test
    fun overlappingInsetsFailSafelyByMaskingWholeFrame() {
        val frame = UiCaptureRgbaFrame(
            width = 2,
            height = 2,
            bytes = byteArrayOf(
                1, 2, 3, 4,
                5, 6, 7, 8,
                9, 10, 11, 12,
                13, 14, 15, 16,
            ),
        )

        redactUiCaptureFrameEdgesInPlace(
            frame,
            UiCaptureEdgeInsets(left = 2, top = 2, right = 2, bottom = 2),
        )

        for (offset in frame.bytes.indices step UI_CAPTURE_RGBA_BYTES_PER_PIXEL) {
            assertArrayEquals(
                byteArrayOf(0, 0, 0, 0xFF.toByte()),
                frame.bytes.copyOfRange(offset, offset + UI_CAPTURE_RGBA_BYTES_PER_PIXEL),
            )
        }
    }

    @Test
    fun invalidInsetsOrDimensionsFailClosed() {
        assertTrue(runCatching { UiCaptureEdgeInsets(left = -1) }.isFailure)
        assertTrue(
            runCatching {
                scaledUiCaptureEdgeInsets(
                    sourceWidth = 0,
                    sourceHeight = 100,
                    targetWidth = 50,
                    targetHeight = 50,
                    sourceInsets = UiCaptureEdgeInsets(top = 10),
                )
            }.isFailure,
        )
    }
}
