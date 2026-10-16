package io.github.arttvad9r.typeddroid

/**
 * Describes how much authority the Android-side execution backend has.
 * This is separate from AgentTrustMode: a trusted agent can still be attached
 * to a device that only exposes standard Android APIs.
 */
enum class PrivilegedBackendKind {
    NONE,
    SHIZUKU_LEGACY,
    EMBEDDED_ADB,
    ROOT,
}

data class PrivilegedBackendCapabilities(
    val kind: PrivilegedBackendKind,
    val rawShell: Boolean,
    val packageManager: Boolean,
    val activityManager: Boolean,
    val appOps: Boolean,
    val systemSettings: Boolean,
    val logcat: Boolean,
    val root: Boolean,
)

object PrivilegedBackendProfiles {
    val None = PrivilegedBackendCapabilities(
        kind = PrivilegedBackendKind.NONE,
        rawShell = false,
        packageManager = false,
        activityManager = false,
        appOps = false,
        systemSettings = false,
        logcat = false,
        root = false,
    )

    val LegacyShizuku = PrivilegedBackendCapabilities(
        kind = PrivilegedBackendKind.SHIZUKU_LEGACY,
        rawShell = false,
        packageManager = true,
        activityManager = true,
        appOps = false,
        systemSettings = false,
        logcat = false,
        root = false,
    )

    val EmbeddedAdb = PrivilegedBackendCapabilities(
        kind = PrivilegedBackendKind.EMBEDDED_ADB,
        rawShell = true,
        packageManager = true,
        activityManager = true,
        appOps = true,
        systemSettings = true,
        logcat = true,
        root = false,
    )

    val Root = PrivilegedBackendCapabilities(
        kind = PrivilegedBackendKind.ROOT,
        rawShell = true,
        packageManager = true,
        activityManager = true,
        appOps = true,
        systemSettings = true,
        logcat = true,
        root = true,
    )
}
