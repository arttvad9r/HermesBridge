package io.github.arttvad9r.hermesbridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiControlPrototypeTest {
    @Test
    fun tapUsesOnlyFixedInputExecutableAndNumericCoordinates() {
        assertArrayEquals(
            arrayOf(
                "/system/bin/input",
                "touchscreen",
                "tap",
                "120",
                "240",
            ),
            buildUiTapCommand(120, 240),
        )
    }

    @Test
    fun swipeUsesOnlyFixedInputExecutableAndBoundedNumericArguments() {
        assertArrayEquals(
            arrayOf(
                "/system/bin/input",
                "touchscreen",
                "swipe",
                "10",
                "20",
                "300",
                "400",
                "350",
            ),
            buildUiSwipeCommand(
                startX = 10,
                startY = 20,
                endX = 300,
                endY = 400,
                durationMillis = 350,
            ),
        )
    }

    @Test
    fun coordinatesAndSwipeDurationFailClosedOutsidePrototypeBounds() {
        assertTrue(isSupportedUiControlCoordinate(0))
        assertTrue(isSupportedUiControlCoordinate(MAX_UI_CONTROL_COORDINATE))
        assertFalse(isSupportedUiControlCoordinate(-1))
        assertFalse(isSupportedUiControlCoordinate(MAX_UI_CONTROL_COORDINATE + 1))

        assertTrue(isSupportedUiSwipeDuration(MIN_UI_SWIPE_DURATION_MILLIS))
        assertTrue(isSupportedUiSwipeDuration(MAX_UI_SWIPE_DURATION_MILLIS))
        assertFalse(isSupportedUiSwipeDuration(MIN_UI_SWIPE_DURATION_MILLIS - 1))
        assertFalse(isSupportedUiSwipeDuration(MAX_UI_SWIPE_DURATION_MILLIS + 1))

        assertTrue(runCatching { buildUiTapCommand(-1, 0) }.isFailure)
        assertTrue(
            runCatching {
                buildUiSwipeCommand(
                    startX = 0,
                    startY = 0,
                    endX = 100,
                    endY = 100,
                    durationMillis = MAX_UI_SWIPE_DURATION_MILLIS + 1,
                )
            }.isFailure
        )
    }

    @Test
    fun prototypeCommandShapeCannotSelectShellDisplayTextOrKeyEvents() {
        val tap = buildUiTapCommand(1, 2).toList()
        val swipe = buildUiSwipeCommand(1, 2, 3, 4, 100).toList()

        listOf(tap, swipe).forEach { argv ->
            assertTrue(argv.first() == UI_INPUT_EXECUTABLE)
            assertTrue(argv[1] == "touchscreen")
            assertFalse(argv.contains("sh"))
            assertFalse(argv.contains("-c"))
            assertFalse(argv.contains("text"))
            assertFalse(argv.contains("keyevent"))
            assertFalse(argv.contains("-d"))
        }
    }
}
