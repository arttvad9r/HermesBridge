package io.github.arttvad9r.hermesbridge

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.PriorityQueue

data class BatteryPowerSummarySnapshot(
    val batteryCapacityMah: Double,
    val computedPowerMah: Double,
    val minDrainedPowerMah: Double,
    val maxDrainedPowerMah: Double,
)

data class BatterySystemPowerItemSnapshot(
    val label: String,
    val mah: Double,
)

data class BatteryUidPowerSnapshot(
    val uid: Int,
    val packageNames: List<String>,
    val mah: Double,
)

data class BatteryWakeLockSnapshot(
    val uid: Int,
    val packageNames: List<String>,
    val name: String,
    val partialTimeMillis: Long,
    val partialCount: Int,
    val backgroundPartialTimeMillis: Long?,
    val backgroundPartialCount: Int?,
)

data class BatteryUsageSnapshot(
    val checkinVersion: Int?,
    val powerSummary: BatteryPowerSummarySnapshot?,
    val systemPowerItems: List<BatterySystemPowerItemSnapshot>,
    val topUids: List<BatteryUidPowerSnapshot>,
    val topPartialWakeLocks: List<BatteryWakeLockSnapshot> = emptyList(),
)

class BatteryStatsParseException(message: String) : IllegalArgumentException(message)

object BatteryStatsCheckinParser {
    fun parse(text: String): BatteryUsageSnapshot {
        val packagesByUid = LinkedHashMap<Int, LinkedHashSet<String>>()
        val uidPower = LinkedHashMap<Int, Double>()
        val systemPower = LinkedHashMap<String, Double>()
        val largestPartialWakeLocks = PriorityQueue<RawWakeLock>(
            compareBy<RawWakeLock> { it.partialTimeMillis }
                .thenBy { it.partialCount }
                .thenBy { it.name.lowercase(Locale.ROOT) }
        )
        var checkinVersion: Int? = null
        var powerSummary: BatteryPowerSummarySnapshot? = null
        var lineCount = 0

        text.lineSequence().forEach { rawLine ->
            lineCount += 1
            if (lineCount > MAX_CHECKIN_LINES) {
                throw BatteryStatsParseException("Battery checkin output contains too many lines.")
            }

            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            val parts = line.split(',')
            if (parts.size < 5) return@forEach

            val ownerUid = parts[1].toIntOrNull() ?: return@forEach
            val mode = parts[2]
            val section = parts[3]

            when {
                mode == "i" && section == "vers" -> {
                    checkinVersion = parts.getOrNull(4)?.toIntOrNull() ?: checkinVersion
                }

                mode == "i" && section == "uid" -> {
                    val mappedUid = parts.getOrNull(4)?.toIntOrNull() ?: return@forEach
                    val packageName = parts.getOrNull(5)
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() && it.length <= MAX_PACKAGE_NAME_LENGTH }
                        ?: return@forEach
                    val packages = packagesByUid.getOrPut(mappedUid) { LinkedHashSet() }
                    if (packages.size < MAX_PACKAGE_NAMES_PER_UID) packages.add(packageName)
                }

                mode == "l" && section == "pws" -> {
                    val capacity = safeMah(parts.getOrNull(4)) ?: return@forEach
                    val computed = safeMah(parts.getOrNull(5)) ?: return@forEach
                    val minDrained = safeMah(parts.getOrNull(6)) ?: return@forEach
                    val maxDrained = safeMah(parts.getOrNull(7)) ?: return@forEach
                    powerSummary = BatteryPowerSummarySnapshot(
                        batteryCapacityMah = capacity,
                        computedPowerMah = computed,
                        minDrainedPowerMah = minDrained,
                        maxDrainedPowerMah = maxDrained,
                    )
                }

                mode == "l" && section == "pwi" -> {
                    val label = parts.getOrNull(4)
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() && it.length <= MAX_LABEL_LENGTH }
                        ?: return@forEach
                    val mah = safeMah(parts.getOrNull(5)) ?: return@forEach

                    if (ownerUid == 0) {
                        val key = label.lowercase(Locale.ROOT)
                        systemPower[key] = maxOf(systemPower[key] ?: 0.0, mah)
                    } else if (label == "uid") {
                        uidPower[ownerUid] = maxOf(uidPower[ownerUid] ?: 0.0, mah)
                    }
                }

                mode == "l" && section == "wl" && ownerUid != 0 -> {
                    val name = parts.getOrNull(4)
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() && it.length <= MAX_WAKELOCK_NAME_LENGTH }
                        ?: return@forEach
                    val partial = timerBeforeMarker(parts, "p") ?: return@forEach
                    if (partial.timeMillis <= 0L) return@forEach
                    val backgroundPartial = timerBeforeMarker(parts, "bp")
                    largestPartialWakeLocks.add(
                        RawWakeLock(
                            uid = ownerUid,
                            name = name,
                            partialTimeMillis = partial.timeMillis,
                            partialCount = partial.count,
                            backgroundPartialTimeMillis = backgroundPartial?.timeMillis,
                            backgroundPartialCount = backgroundPartial?.count,
                        )
                    )
                    if (largestPartialWakeLocks.size > MAX_TOP_WAKELOCKS) {
                        largestPartialWakeLocks.poll()
                    }
                }
            }
        }

        if (
            powerSummary == null &&
            systemPower.isEmpty() &&
            uidPower.isEmpty() &&
            largestPartialWakeLocks.isEmpty()
        ) {
            throw BatteryStatsParseException("Battery checkin output contained no supported diagnostic sections.")
        }

        val systemItems = systemPower.entries
            .asSequence()
            .sortedByDescending { it.value }
            .take(MAX_SYSTEM_POWER_ITEMS)
            .map { (label, mah) -> BatterySystemPowerItemSnapshot(label, mah) }
            .toList()

        val topUids = uidPower.entries
            .asSequence()
            .sortedByDescending { it.value }
            .take(MAX_TOP_UIDS)
            .map { (uid, mah) ->
                BatteryUidPowerSnapshot(
                    uid = uid,
                    packageNames = packagesByUid[uid]?.toList().orEmpty(),
                    mah = mah,
                )
            }
            .toList()

        val topWakeLocks = largestPartialWakeLocks.toList()
            .sortedWith(
                compareByDescending<RawWakeLock> { it.partialTimeMillis }
                    .thenByDescending { it.partialCount }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            )
            .map { wakeLock ->
                BatteryWakeLockSnapshot(
                    uid = wakeLock.uid,
                    packageNames = packagesByUid[wakeLock.uid]?.toList().orEmpty(),
                    name = wakeLock.name,
                    partialTimeMillis = wakeLock.partialTimeMillis,
                    partialCount = wakeLock.partialCount,
                    backgroundPartialTimeMillis = wakeLock.backgroundPartialTimeMillis,
                    backgroundPartialCount = wakeLock.backgroundPartialCount,
                )
            }

        return BatteryUsageSnapshot(
            checkinVersion = checkinVersion,
            powerSummary = powerSummary,
            systemPowerItems = systemItems,
            topUids = topUids,
            topPartialWakeLocks = topWakeLocks,
        )
    }

    private fun safeMah(value: String?): Double? {
        val parsed = value?.trim()?.toDoubleOrNull() ?: return null
        return parsed.takeIf { it.isFinite() && it >= 0.0 }
    }

    private fun timerBeforeMarker(parts: List<String>, marker: String): WakeLockTimer? {
        val markerIndex = (5 until parts.size).firstOrNull { parts[it] == marker } ?: return null
        if (markerIndex <= 5 || markerIndex + 1 >= parts.size) return null
        val timeMillis = parts[markerIndex - 1].trim().toLongOrNull()
            ?.takeIf { it >= 0L }
            ?: return null
        val count = parts[markerIndex + 1].trim().toIntOrNull()
            ?.takeIf { it >= 0 }
            ?: return null
        return WakeLockTimer(timeMillis, count)
    }

    private data class WakeLockTimer(
        val timeMillis: Long,
        val count: Int,
    )

    private data class RawWakeLock(
        val uid: Int,
        val name: String,
        val partialTimeMillis: Long,
        val partialCount: Int,
        val backgroundPartialTimeMillis: Long?,
        val backgroundPartialCount: Int?,
    )

    private const val MAX_CHECKIN_LINES = 100_000
    private const val MAX_PACKAGE_NAME_LENGTH = 255
    private const val MAX_PACKAGE_NAMES_PER_UID = 8
    private const val MAX_LABEL_LENGTH = 80
    private const val MAX_WAKELOCK_NAME_LENGTH = 200
    private const val MAX_SYSTEM_POWER_ITEMS = 32
    private const val MAX_TOP_UIDS = 30
    private const val MAX_TOP_WAKELOCKS = 30
}

data class BatteryDiagnosticsResult(
    val ok: Boolean,
    val snapshot: BatteryUsageSnapshot? = null,
    val code: String? = null,
    val message: String? = null,
)

interface BatteryDiagnosticsBackend {
    fun readiness(): PrivilegedBackendReadiness
    suspend fun readUsage(): BatteryDiagnosticsResult
}

class ShizukuBatteryDiagnosticsBackend : BatteryDiagnosticsBackend {
    override fun readiness(): PrivilegedBackendReadiness =
        ShizukuPrivilegedUserServiceClient.readiness()

    override suspend fun readUsage(): BatteryDiagnosticsResult {
        val transport = ShizukuPrivilegedUserServiceClient.readBatteryStats()
        if (!transport.ok) {
            return BatteryDiagnosticsResult(
                ok = false,
                code = transport.code,
                message = transport.message,
            )
        }
        if (transport.truncated) {
            return BatteryDiagnosticsResult(
                ok = false,
                code = "battery_stats_too_large",
                message = "Battery diagnostics exceeded the ${MAX_BATTERY_STATS_OUTPUT_BYTES / (1024 * 1024)} MiB safety limit.",
            )
        }

        val text = transport.text ?: return BatteryDiagnosticsResult(
            ok = false,
            code = "battery_stats_stream_failed",
            message = "Battery diagnostics returned no streamed output.",
        )
        val snapshot = try {
            BatteryStatsCheckinParser.parse(text)
        } catch (error: BatteryStatsParseException) {
            return BatteryDiagnosticsResult(
                ok = false,
                code = "battery_stats_parse_failed",
                message = error.message ?: "Battery diagnostics could not be parsed.",
            )
        }

        return BatteryDiagnosticsResult(ok = true, snapshot = snapshot)
    }
}

internal data class BoundedBatteryStatsText(
    val text: String,
    val truncated: Boolean,
)

internal fun readBoundedBatteryStats(
    input: InputStream,
    limitBytes: Int = MAX_BATTERY_STATS_OUTPUT_BYTES,
): BoundedBatteryStatsText {
    require(limitBytes > 0) { "Battery diagnostics output limit must be positive." }
    val output = ByteArrayOutputStream(minOf(limitBytes, 64 * 1024))
    val buffer = ByteArray(16 * 1024)
    var retained = 0
    var truncated = false

    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (retained < limitBytes) {
            val accepted = minOf(read, limitBytes - retained)
            output.write(buffer, 0, accepted)
            retained += accepted
            if (accepted < read) truncated = true
        } else {
            truncated = true
        }
    }

    return BoundedBatteryStatsText(
        text = output.toString(Charsets.UTF_8.name()),
        truncated = truncated,
    )
}

internal const val MAX_BATTERY_STATS_OUTPUT_BYTES = 4 * 1024 * 1024

internal fun buildBatteryStatsCommand(): Array<String> =
    arrayOf("dumpsys", "batterystats", "-c", "--charged")

object DisabledBatteryDiagnosticsBackend : BatteryDiagnosticsBackend {
    override fun readiness() = PrivilegedBackendReadiness(
        ready = false,
        code = "shizuku_unavailable",
        message = "Battery diagnostics backend is not configured.",
    )

    override suspend fun readUsage() = BatteryDiagnosticsResult(
        ok = false,
        code = "shizuku_unavailable",
        message = "Battery diagnostics backend is not configured.",
    )
}