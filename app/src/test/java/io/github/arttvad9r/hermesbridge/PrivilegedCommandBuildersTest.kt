package io.github.arttvad9r.hermesbridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegedCommandBuildersTest {
    @Test
    fun installCommandUsesOnlyServiceGeneratedStagingPath() {
        val stagedPath = createShizukuApkStagingPath("12345678-1234-1234-1234-1234567890ab")
        assertArrayEquals(
            arrayOf("pm", "install", stagedPath),
            buildPmInstallCommand(stagedApkPath = stagedPath, replace = false),
        )
        assertArrayEquals(
            arrayOf("pm", "install", "-r", stagedPath),
            buildPmInstallCommand(stagedApkPath = stagedPath, replace = true),
        )
        assertTrue(runCatching { buildPmInstallCommand("/data/local/tmp/arbitrary.apk", false) }.isFailure)
        assertTrue(runCatching { buildPmInstallCommand("$stagedPath;id", false) }.isFailure)
        assertTrue(runCatching { buildPmInstallCommand("../escape.apk", false) }.isFailure)
    }

    @Test
    fun uninstallCommandOnlyAcceptsValidatedPackageName() {
        assertArrayEquals(
            arrayOf("pm", "uninstall", "com.example.app"),
            buildPmUninstallCommand("com.example.app", keepData = false),
        )
        assertArrayEquals(
            arrayOf("pm", "uninstall", "-k", "com.example.app"),
            buildPmUninstallCommand("com.example.app", keepData = true),
        )
        assertTrue(runCatching { buildPmUninstallCommand("../bad", false) }.isFailure)
    }

    @Test
    fun forceStopCommandOnlyAcceptsValidatedPackageName() {
        assertArrayEquals(
            arrayOf("am", "force-stop", "com.example.app"),
            buildAmForceStopCommand("com.example.app"),
        )
        assertTrue(runCatching { buildAmForceStopCommand("com.example.app;id") }.isFailure)
    }

    @Test
    fun revokeCommandBindsExactPackagePermissionAndUser() {
        assertArrayEquals(
            arrayOf(
                "pm",
                "revoke",
                "--user",
                "10",
                "com.example.app",
                "android.permission.CAMERA",
            ),
            buildPmRevokePermissionCommand(
                packageName = "com.example.app",
                permissionName = "android.permission.CAMERA",
                userId = 10,
            ),
        )

        assertTrue(
            runCatching {
                buildPmRevokePermissionCommand(
                    packageName = "com.example.app;id",
                    permissionName = "android.permission.CAMERA",
                    userId = 10,
                )
            }.isFailure
        )
        assertTrue(
            runCatching {
                buildPmRevokePermissionCommand(
                    packageName = "com.example.app",
                    permissionName = "android.permission.CAMERA --user 0",
                    userId = 10,
                )
            }.isFailure
        )
        assertTrue(
            runCatching {
                buildPmRevokePermissionCommand(
                    packageName = "com.example.app",
                    permissionName = "android.permission.CAMERA",
                    userId = -1,
                )
            }.isFailure
        )
    }

    @Test
    fun androidUserIdValidationIsBounded() {
        assertTrue(isSupportedAndroidUserId(0))
        assertTrue(isSupportedAndroidUserId(99_999))
        assertFalse(isSupportedAndroidUserId(-1))
        assertFalse(isSupportedAndroidUserId(100_000))
    }

    @Test
    fun androidNamesRejectShellMetacharactersAndPaths() {
        assertTrue(isValidAndroidQualifiedName("com.example.PERMISSION"))
        assertFalse(isValidAndroidQualifiedName("com.example.app --user 0"))
        assertFalse(isValidAndroidQualifiedName("com.example.app;id"))
        assertFalse(isValidAndroidQualifiedName("/system/bin/sh"))
        assertFalse(isValidAndroidQualifiedName("../escape"))
    }
}
