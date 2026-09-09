package io.github.arttvad9r.hermesbridge

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.core.content.pm.PackageInfoCompat
import java.util.Locale

data class InstalledAppSnapshot(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val versionCode: Long,
    val systemApp: Boolean,
    val enabled: Boolean,
)

interface InstalledAppsRepository {
    fun listLaunchableApps(): List<InstalledAppSnapshot>
}

class AndroidInstalledAppsRepository(context: Context) : InstalledAppsRepository {
    private val packageManager = context.applicationContext.packageManager

    @Suppress("DEPRECATION")
    override fun listLaunchableApps(): List<InstalledAppSnapshot> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        return packageManager.queryIntentActivities(launcherIntent, 0)
            .asSequence()
            .mapNotNull { resolveInfo ->
                val applicationInfo = resolveInfo.activityInfo?.applicationInfo ?: return@mapNotNull null
                val packageName = applicationInfo.packageName ?: return@mapNotNull null
                val packageInfo = runCatching {
                    packageManager.getPackageInfo(packageName, 0)
                }.getOrNull() ?: return@mapNotNull null

                InstalledAppSnapshot(
                    packageName = packageName,
                    label = packageManager.getApplicationLabel(applicationInfo).toString(),
                    versionName = packageInfo.versionName,
                    versionCode = PackageInfoCompat.getLongVersionCode(packageInfo),
                    systemApp = applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                    enabled = applicationInfo.enabled,
                )
            }
            .distinctBy { it.packageName }
            .sortedWith(
                compareBy<InstalledAppSnapshot> { it.label.lowercase(Locale.ROOT) }
                    .thenBy { it.packageName.lowercase(Locale.ROOT) }
            )
            .take(MAX_APPS)
            .toList()
    }

    companion object {
        const val MAX_APPS = 500
    }
}
