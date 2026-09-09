package io.github.arttvad9r.hermesbridge

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

data class AppUsageSnapshot(
    val packageName: String,
    val lastTimeUsedEpochMillis: Long?,
    val totalTimeForegroundMillis: Long,
)

interface AppUsageRepository {
    fun hasAccess(): Boolean
    fun query(days: Int): List<AppUsageSnapshot>
}

class AndroidAppUsageRepository(context: Context) : AppUsageRepository {
    private val appContext = context.applicationContext
    private val usageStatsManager = appContext.getSystemService(UsageStatsManager::class.java)
    private val appOpsManager = appContext.getSystemService(AppOpsManager::class.java)

    override fun hasAccess(): Boolean {
        val mode = runCatching {
            appOpsManager.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                appContext.packageName,
            )
        }.getOrDefault(AppOpsManager.MODE_ERRORED)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    override fun query(days: Int): List<AppUsageSnapshot> {
        require(days in MIN_DAYS..MAX_DAYS) { "Usage window must be between $MIN_DAYS and $MAX_DAYS days." }
        if (!hasAccess()) throw UsageAccessNotGrantedException()

        val end = System.currentTimeMillis()
        val duration = days.toLong() * MILLIS_PER_DAY
        val begin = (end - duration).coerceAtLeast(0L)
        val aggregated = usageStatsManager.queryAndAggregateUsageStats(begin, end)

        return aggregated.values
            .asSequence()
            .mapNotNull { stats ->
                val packageName = stats.packageName?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                AppUsageSnapshot(
                    packageName = packageName,
                    lastTimeUsedEpochMillis = stats.lastTimeUsed.takeIf { it in begin..end },
                    totalTimeForegroundMillis = stats.totalTimeInForeground.coerceAtLeast(0L),
                )
            }
            .sortedWith(
                compareByDescending<AppUsageSnapshot> { it.lastTimeUsedEpochMillis ?: Long.MIN_VALUE }
                    .thenByDescending { it.totalTimeForegroundMillis }
                    .thenBy { it.packageName },
            )
            .take(MAX_RESULTS)
            .toList()
    }

    companion object {
        const val MIN_DAYS = 1
        const val MAX_DAYS = 365
        const val MAX_RESULTS = 2_000
        private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1_000L
    }
}

class UsageAccessNotGrantedException : IllegalStateException(
    "Android Usage Access has not been granted to Hermes Bridge."
)
