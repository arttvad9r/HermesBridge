package io.github.arttvad9r.hermesbridge

import io.github.arttvad9r.hermesbridge.protocol.CommandRequestPayload
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryToolRegistryTest {
    private val healthRepository = object : DeviceHealthRepository {
        override fun snapshot() = DeviceHealthSnapshot(null, 1, 2, 3, 4)
    }

    private val appsRepository = object : InstalledAppsRepository {
        override fun listLaunchableApps() = emptyList<InstalledAppSnapshot>()
    }

    private val filesRepository = object : SafFilesRepository {
        override fun list(pathSegments: List<String>) = SafDirectorySnapshot(
            rootName = "Shared",
            pathSegments = pathSegments,
            entries = emptyList(),
        )
    }

    @Test
    fun batteryUsageExecutesReadOnlyWithoutApproval() = runBlocking {
        BridgeApprovalRuntime.clear()
        val backend = RecordingBatteryBackend()
        val result = registry(backend).execute(
            CommandRequestPayload(
                tool = BridgeToolRegistry.BATTERY_USAGE,
                requestId = "battery-1",
            )
        )

        assertTrue(result.ok)
        assertEquals(1, backend.readCalls)
        assertTrue(BridgeApprovalRuntime.pendingTickets.value.isEmpty())
        assertEquals(
            "5000.0",
            result.result
                ?.get("powerSummary")
                ?.let { it as kotlinx.serialization.json.JsonObject }
                ?.get("batteryCapacityMah")
                ?.toString(),
        )
        val serialized = result.result?.toString().orEmpty()
        assertTrue(serialized.contains("com.example.app"))
        assertTrue(serialized.contains("ExampleLock"))
        assertTrue(serialized.contains("120000"))
    }

    @Test
    fun batteryUsageRejectsArgumentsBeforeBackendExecution() = runBlocking {
        val backend = RecordingBatteryBackend()
        val result = registry(backend).execute(
            CommandRequestPayload(
                tool = BridgeToolRegistry.BATTERY_USAGE,
                requestId = "battery-args",
                arguments = buildJsonObject { put("raw", true) },
            )
        )

        assertFalse(result.ok)
        assertEquals("invalid_arguments", result.error?.code)
        assertEquals(0, backend.readCalls)
    }

    @Test
    fun batteryUsageFailsClosedWhenShizukuIsUnavailable() = runBlocking {
        val backend = RecordingBatteryBackend(
            readiness = PrivilegedBackendReadiness(
                ready = false,
                code = "shizuku_unavailable",
                message = "not ready",
            )
        )
        val result = registry(backend).execute(
            CommandRequestPayload(
                tool = BridgeToolRegistry.BATTERY_USAGE,
                requestId = "battery-no-shizuku",
            )
        )

        assertFalse(result.ok)
        assertEquals("shizuku_unavailable", result.error?.code)
        assertEquals(0, backend.readCalls)
    }

    private fun registry(batteryBackend: BatteryDiagnosticsBackend) = BridgeToolRegistry(
        healthRepository = healthRepository,
        appsRepository = appsRepository,
        filesRepository = filesRepository,
        batteryDiagnosticsBackend = batteryBackend,
    )

    private class RecordingBatteryBackend(
        private val readiness: PrivilegedBackendReadiness = PrivilegedBackendReadiness(ready = true),
    ) : BatteryDiagnosticsBackend {
        var readCalls = 0

        override fun readiness() = readiness

        override suspend fun readUsage(): BatteryDiagnosticsResult {
            readCalls += 1
            return BatteryDiagnosticsResult(
                ok = true,
                snapshot = BatteryUsageSnapshot(
                    checkinVersion = 11,
                    powerSummary = BatteryPowerSummarySnapshot(
                        batteryCapacityMah = 5000.0,
                        computedPowerMah = 200.0,
                        minDrainedPowerMah = 180.0,
                        maxDrainedPowerMah = 220.0,
                    ),
                    systemPowerItems = listOf(
                        BatterySystemPowerItemSnapshot("screen", 80.0)
                    ),
                    topUids = listOf(
                        BatteryUidPowerSnapshot(
                            uid = 10123,
                            packageNames = listOf("com.example.app"),
                            mah = 55.0,
                        )
                    ),
                    topPartialWakeLocks = listOf(
                        BatteryWakeLockSnapshot(
                            uid = 10123,
                            packageNames = listOf("com.example.app"),
                            name = "ExampleLock",
                            partialTimeMillis = 120000L,
                            partialCount = 17,
                            backgroundPartialTimeMillis = 90000L,
                            backgroundPartialCount = 12,
                        )
                    ),
                ),
            )
        }
    }
}
