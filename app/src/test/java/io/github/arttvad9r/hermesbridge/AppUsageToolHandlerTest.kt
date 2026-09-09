package io.github.arttvad9r.hermesbridge

import io.github.arttvad9r.hermesbridge.protocol.CommandRequestPayload
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUsageToolHandlerTest {
    private val launcherApps = object : InstalledAppsRepository {
        override fun listLaunchableApps() = listOf(
            InstalledAppSnapshot(
                packageName = "com.example.used",
                label = "Used",
                versionName = "1",
                versionCode = 1,
                systemApp = false,
                enabled = true,
            ),
            InstalledAppSnapshot(
                packageName = "com.example.no.record",
                label = "No record",
                versionName = "1",
                versionCode = 1,
                systemApp = false,
                enabled = true,
            ),
        )
    }

    @Test
    fun usageReturnsOnlyLauncherVisibleAppsAndMarksObservationExplicitly() {
        val repository = FakeUsageRepository(
            entries = listOf(
                AppUsageSnapshot("com.example.used", 900L, 12_000L),
                AppUsageSnapshot("com.example.hidden", 950L, 99_000L),
            )
        )
        val result = AppUsageToolHandler(launcherApps, repository).execute(
            CommandRequestPayload(
                tool = AppUsageToolHandler.TOOL_NAME,
                requestId = "usage-1",
                arguments = buildJsonObject { put("days", 30) },
            )
        )

        assertTrue(result.ok)
        val serialized = result.result.toString()
        assertTrue(serialized.contains("com.example.used"))
        assertTrue(serialized.contains("com.example.no.record"))
        assertFalse(serialized.contains("com.example.hidden"))
        assertTrue(serialized.contains("\"usageObserved\":true"))
        assertTrue(serialized.contains("\"usageObserved\":false"))
        assertEquals(30, repository.lastDays)
    }

    @Test
    fun usageDefaultsToThirtyDays() {
        val repository = FakeUsageRepository(emptyList())
        val result = AppUsageToolHandler(launcherApps, repository).execute(
            CommandRequestPayload(
                tool = AppUsageToolHandler.TOOL_NAME,
                requestId = "usage-default",
            )
        )
        assertTrue(result.ok)
        assertEquals(30, repository.lastDays)
    }

    @Test
    fun usageRejectsInvalidWindowBeforeQuery() {
        val repository = FakeUsageRepository(emptyList())
        for (days in listOf(0, 366)) {
            val result = AppUsageToolHandler(launcherApps, repository).execute(
                CommandRequestPayload(
                    tool = AppUsageToolHandler.TOOL_NAME,
                    requestId = "usage-invalid-$days",
                    arguments = buildJsonObject { put("days", days) },
                )
            )
            assertFalse(result.ok)
            assertEquals("invalid_arguments", result.error?.code)
        }
        assertEquals(null, repository.lastDays)
    }

    @Test
    fun usageFailsClosedWithoutSpecialAccess() {
        val repository = FakeUsageRepository(emptyList(), access = false)
        val result = AppUsageToolHandler(launcherApps, repository).execute(
            CommandRequestPayload(
                tool = AppUsageToolHandler.TOOL_NAME,
                requestId = "usage-no-access",
            )
        )
        assertFalse(result.ok)
        assertEquals("usage_access_not_granted", result.error?.code)
        assertEquals(null, repository.lastDays)
    }

    private class FakeUsageRepository(
        private val entries: List<AppUsageSnapshot>,
        private val access: Boolean = true,
    ) : AppUsageRepository {
        var lastDays: Int? = null

        override fun hasAccess() = access

        override fun query(days: Int): AppUsageWindowSnapshot {
            lastDays = days
            return AppUsageWindowSnapshot(
                days = days,
                beginEpochMillis = 100L,
                endEpochMillis = 1_000L,
                entries = entries,
            )
        }
    }
}
