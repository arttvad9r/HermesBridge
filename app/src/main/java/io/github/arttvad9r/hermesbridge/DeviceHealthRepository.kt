package io.github.arttvad9r.hermesbridge

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs

interface DeviceHealthRepository {
    fun snapshot(): DeviceHealthSnapshot
}

class AndroidDeviceHealthRepository(
    private val context: Context,
) : DeviceHealthRepository {
    override fun snapshot(): DeviceHealthSnapshot {
        val batteryManager = context.getSystemService(BatteryManager::class.java)
        val battery = batteryManager
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }

        val activityManager = context.getSystemService(ActivityManager::class.java)
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memoryInfo)

        val statFs = StatFs(Environment.getDataDirectory().absolutePath)

        return DeviceHealthSnapshot(
            batteryPercent = battery,
            availableMemoryBytes = memoryInfo.availMem,
            totalMemoryBytes = memoryInfo.totalMem,
            availableStorageBytes = statFs.availableBytes,
            totalStorageBytes = statFs.totalBytes,
        )
    }
}
