package io.github.arttvad9r.hermesbridge;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;

/**
 * Privileged operations exposed only to the Hermes Bridge app process.
 *
 * Keep this interface typed: no argv, shell command, or generic execution method
 * may cross the Binder boundary.
 */
interface IPrivilegedBridgeService {
    void destroy() = 16777114;

    Bundle installApk(in ParcelFileDescriptor apk, long sizeBytes, boolean replace) = 1;
    Bundle uninstallApp(String packageName, boolean keepData) = 2;
    Bundle forceStopApp(String packageName) = 3;
    Bundle revokePermission(String packageName, String permissionName, int userId) = 4;
    Bundle writeBatteryStats(in ParcelFileDescriptor output) = 5;
}