package io.github.arttvad9r.typeddroid.adb

import android.os.Process
import io.github.arttvad9r.typeddroid.BuildConfig
import io.github.arttvad9r.typeddroid.EmbeddedPrivilegedClient
import io.github.arttvad9r.typeddroid.EmbeddedPrivilegedStore
import java.util.UUID

internal data class EmbeddedAdbBootstrapResult(
    val socketName: String,
    val apkPath: String,
)

/** Starts the TypedDroid-owned shell-UID helper from the installed APK. */
internal class EmbeddedAdbBootstrap(
    private val store: EmbeddedPrivilegedStore? = null,
) {
    fun bootstrap(connection: AdbSecureConnection): EmbeddedAdbBootstrapResult {
        val shell = connection.shellExecutor()
        val apkPath = discoverBaseApk(shell)
        val socketName = "typeddroid-" + UUID.randomUUID().toString().replace("-", "")
        val command = buildBootstrapCommand(
            apkPath = apkPath,
            socketName = socketName,
            appUid = Process.myUid(),
        )
        val result = shell.execute(command)
        check(result.exitCode == 0) {
            "Could not start embedded privileged helper: ${result.stderr.decodeToString().take(200)}"
        }
        if (store != null) {
            check(waitForHelper(socketName)) {
                "Embedded privileged helper started but its authenticated local socket did not become ready."
            }
            store.saveSocketName(socketName)
        }
        return EmbeddedAdbBootstrapResult(socketName, apkPath)
    }

    private fun waitForHelper(socketName: String): Boolean {
        repeat(HELPER_READY_ATTEMPTS) {
            if (EmbeddedPrivilegedClient(socketName).ping()) return true
            Thread.sleep(HELPER_READY_DELAY_MILLIS)
        }
        return false
    }

    internal fun discoverBaseApk(shell: AdbShellExecutor): String {
        val result = shell.execute("pm path ${shellQuote(BuildConfig.APPLICATION_ID)}")
        check(result.exitCode == 0) { "Package manager could not locate the TypedDroid APK." }
        val paths = result.stdout.decodeToString()
            .lineSequence()
            .map(String::trim)
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:") }
            .toList()
        val base = paths.singleOrNull { it.endsWith("/base.apk") }
            ?: paths.singleOrNull()
            ?: error("Could not identify TypedDroid base APK path.")
        require(base.startsWith("/data/app/") && base.endsWith(".apk") && '\n' !in base && '\r' !in base) {
            "Package manager returned an unexpected APK path."
        }
        return base
    }

    private companion object {
        const val HELPER_READY_ATTEMPTS = 40
        const val HELPER_READY_DELAY_MILLIS = 50L
    }
}

internal fun buildBootstrapCommand(
    apkPath: String,
    socketName: String,
    appUid: Int,
): String {
    require(apkPath.startsWith("/data/app/") && apkPath.endsWith(".apk")) { "Invalid bootstrap APK path." }
    require(Regex("^typeddroid-[a-f0-9]{32}$").matches(socketName)) { "Invalid bootstrap socket name." }
    require(appUid >= 10_000) { "Invalid bootstrap app UID." }
    return buildString {
        append("CLASSPATH=")
        append(shellQuote(apkPath))
        append(" /system/bin/app_process /system/bin ")
        append("io.github.arttvad9r.typeddroid.EmbeddedPrivilegedMain")
        append(" --socket ")
        append(shellQuote(socketName))
        append(" --app-uid ")
        append(appUid)
        append(" </dev/null >/dev/null 2>&1 &")
    }
}

internal fun shellQuote(value: String): String {
    require('\u0000' !in value) { "Shell value contains NUL." }
    return "'" + value.replace("'", "'\"'\"'") + "'"
}
