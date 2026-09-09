package io.github.arttvad9r.hermesbridge

import android.content.pm.PackageManager
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.lang.reflect.InvocationTargetException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku

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

data class BatteryUsageSnapshot(
    val checkinVersion: Int?,
    val powerSummary: BatteryPowerSummarySnapshot?,
    val systemPowerItems: List<BatterySystemPowerItemSnapshot>,
    val topUids: List<BatteryUidPowerSnapshot>,
)

class BatteryStatsParseException(message: String) : IllegalArgumentException(message)

object BatteryStatsCheckinParser {
    fun parse(text: String): BatteryUsageSnapshot {
        val packagesByUid = LinkedHashMap<Int, LinkedHashSet<String>>()
        val uidPower = LinkedHashMap<Int, Double>()
        val systemPower = LinkedHashMap<String, Double>()
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
            }
        }

        if (powerSummary == null && systemPower.isEmpty() && uidPower.isEmpty()) {
            throw BatteryStatsParseException("Battery checkin output contained no supported power-use sections.")
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

        return BatteryUsageSnapshot(
            checkinVersion = checkinVersion,
            powerSummary = powerSummary,
            systemPowerItems = systemItems,
            topUids = topUids,
        )
    }

    private fun safeMah(value: String?): Double? {
        val parsed = value?.trim()?.toDoubleOrNull() ?: return null
        return parsed.takeIf { it.isFinite() && it >= 0.0 }
    }

    private const val MAX_CHECKIN_LINES = 100_000
    private const val MAX_PACKAGE_NAME_LENGTH = 255
    private const val MAX_PACKAGE_NAMES_PER_UID = 8
    private const val MAX_LABEL_LENGTH = 80
    private const val MAX_SYSTEM_POWER_ITEMS = 32
    private const val MAX_TOP_UIDS = 30
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
    override fun readiness(): PrivilegedBackendReadiness {
        val localState = ShizukuRuntime.state.value
        if (localState.status != ShizukuAccessStatus.READY) {
            return PrivilegedBackendReadiness(
                ready = false,
                code = "shizuku_unavailable",
                message = localState.message ?: "Shizuku is not ready for battery diagnostics.",
            )
        }

        val binderReady = runCatching {
            Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        return if (binderReady) {
            PrivilegedBackendReadiness(ready = true)
        } else {
            PrivilegedBackendReadiness(
                ready = false,
                code = "shizuku_unavailable",
                message = "Shizuku binder or permission is no longer available.",
            )
        }
    }

    override suspend fun readUsage(): BatteryDiagnosticsResult = withContext(Dispatchers.IO) {
        val readiness = readiness()
        if (!readiness.ready) {
            return@withContext BatteryDiagnosticsResult(
                ok = false,
                code = readiness.code,
                message = readiness.message,
            )
        }

        val command = try {
            executeBatteryStatsCommand()
        } catch (_: TimeoutCancellationException) {
            return@withContext BatteryDiagnosticsResult(
                ok = false,
                code = "battery_stats_timeout",
                message = "Battery diagnostics did not finish within ${BATTERY_STATS_TIMEOUT_MILLIS / 1000} seconds.",
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return@withContext BatteryDiagnosticsResult(
                ok = false,
                code = "shizuku_spawn_failed",
                message = (error.message ?: error::class.java.simpleName).take(200),
            )
        }

        if (command.exitCode != 0) {
            return@withContext BatteryDiagnosticsResult(
                ok = false,
                code = "battery_stats_failed",
                message = command.firstOutputLine() ?: "dumpsys batterystats failed.",
            )
        }
        if (command.stdout.truncated) {
            return@withContext BatteryDiagnosticsResult(
                ok = false,
                code = "battery_stats_too_large",
                message = "Battery diagnostics exceeded the ${MAX_STDOUT_BYTES / (1024 * 1024)} MiB safety limit.",
            )
        }

        val snapshot = try {
            BatteryStatsCheckinParser.parse(command.stdout.text)
        } catch (error: BatteryStatsParseException) {
            return@withContext BatteryDiagnosticsResult(
                ok = false,
                code = "battery_stats_parse_failed",
                message = error.message ?: "Battery diagnostics could not be parsed.",
            )
        }

        BatteryDiagnosticsResult(ok = true, snapshot = snapshot)
    }

    private suspend fun executeBatteryStatsCommand(): BatteryCommandResult {
        if (!Shizuku.pingBinder()) error("Shizuku binder is not reachable.")
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            error("Shizuku permission is not granted to Hermes Bridge.")
        }

        val process = spawnViaShizuku(buildBatteryStatsCommand())
        return withTimeout(BATTERY_STATS_TIMEOUT_MILLIS) {
            coroutineScope {
                runCatching { process.outputStream.close() }
                val stdoutDeferred = async(Dispatchers.IO) {
                    readBounded(process.inputStream, MAX_STDOUT_BYTES)
                }
                val stderrDeferred = async(Dispatchers.IO) {
                    readBounded(process.errorStream, MAX_STDERR_BYTES)
                }

                try {
                    val exitCode = runInterruptible(Dispatchers.IO) { process.waitFor() }
                    BatteryCommandResult(
                        exitCode = exitCode,
                        stdout = stdoutDeferred.await(),
                        stderr = stderrDeferred.await(),
                    )
                } finally {
                    runCatching { process.destroy() }
                }
            }
        }
    }

    private fun spawnViaShizuku(argv: Array<String>): Process {
        val method = newProcessMethod
            ?: error("Shizuku.newProcess is unavailable in the linked Shizuku API.")
        return try {
            method.invoke(null, argv, null, null) as Process
        } catch (error: InvocationTargetException) {
            val cause = error.targetException ?: error
            throw IllegalStateException(
                "Shizuku.newProcess failed: ${cause.message ?: cause::class.java.simpleName}",
                cause,
            )
        } catch (error: ReflectiveOperationException) {
            throw IllegalStateException("Shizuku.newProcess reflection failed: ${error.message}", error)
        }
    }

    private fun readBounded(input: InputStream, limitBytes: Int): BoundedText {
        val output = ByteArrayOutputStream(minOf(limitBytes, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        var retained = 0
        var truncated = false

        input.use { stream ->
            while (true) {
                val read = stream.read(buffer)
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
        }

        return BoundedText(
            text = output.toString(Charsets.UTF_8.name()),
            truncated = truncated,
        )
    }

    private data class BoundedText(
        val text: String,
        val truncated: Boolean,
    )

    private data class BatteryCommandResult(
        val exitCode: Int,
        val stdout: BoundedText,
        val stderr: BoundedText,
    ) {
        fun firstOutputLine(): String? = sequenceOf(stderr.text, stdout.text)
            .flatMap { it.lineSequence() }
            .map(String::trim)
            .firstOrNull(String::isNotEmpty)
            ?.take(200)
    }

    companion object {
        private const val BATTERY_STATS_TIMEOUT_MILLIS = 45_000L
        private const val MAX_STDOUT_BYTES = 4 * 1024 * 1024
        private const val MAX_STDERR_BYTES = 64 * 1024

        @Volatile
        private var cachedNewProcessMethod: java.lang.reflect.Method? = null

        private val newProcessMethod: java.lang.reflect.Method?
            get() = cachedNewProcessMethod ?: runCatching {
                Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java,
                ).apply { isAccessible = true }
            }.getOrNull().also { cachedNewProcessMethod = it }
    }
}

internal fun buildBatteryStatsCommand(): Array<String> =
    arrayOf("dumpsys", "batterystats", "--charged", "--checkin")

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
