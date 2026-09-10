package io.github.arttvad9r.hermesbridge

import io.github.arttvad9r.hermesbridge.protocol.CommandRequestPayload
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUsageResultBoundsTest {
    @Test
    fun usageReturnsMostRecentBoundedLauncherVisibleApps() {
        val apps = (0 until 200).map { index ->
            InstalledAppSnapshot(
                packageName = packageName(index),
                label = "\u0800".repeat(300),
                versionName = "unused",
                versionCode = index.toLong(),
                systemApp = false,
                enabled = true,
            )
        }
        val usageEntries = apps.mapIndexed { index, app ->
            AppUsageSnapshot(
                packageName = app.packageName,
                lastTimeUsedEpochMillis = index.toLong(),
                totalTimeForegroundMillis = index.toLong(),
            )
        }
        val handler = AppUsageToolHandler(
            appsRepository = object : InstalledAppsRepository {
                override fun listLaunchableApps() = apps
            },
            usageRepository = object : AppUsageRepository {
                override fun hasAccess() = true

                override fun query(days: Int) = AppUsageWindowSnapshot(
                    days = days,
                    beginEpochMillis = 0L,
                    endEpochMillis = 1_000L,
                    entries = usageEntries,
                )
            },
        )

        val result = handler.execute(
            CommandRequestPayload(
                tool = AppUsageToolHandler.TOOL_NAME,
                requestId = "usage-bounds",
            )
        )

        assertTrue(result.ok)
        assertEquals(MAX_REMOTE_APP_ENTRIES.toString(), result.result?.get("count")?.toString())
        assertEquals("200", result.result?.get("totalVisibleCount")?.toString())
        assertEquals("true", result.result?.get("truncated")?.toString())

        val returned = result.result?.get("apps") as JsonArray
        val serialized = returned.toString()
        assertTrue(serialized.contains(packageName(199)))
        assertFalse(serialized.contains(packageName(0)))
        assertTrue(serialized.contains("\"lastTimeUsedEpochMillis\":199"))
    }

    private fun packageName(index: Int): String =
        "com.example.app" + index.toString().padStart(3, '0')
}
