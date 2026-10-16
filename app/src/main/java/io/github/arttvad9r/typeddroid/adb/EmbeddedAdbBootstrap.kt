package io.github.arttvad9r.typeddroid.adb

import io.github.arttvad9r.typeddroid.BuildConfig
import io.github.arttvad9r.typeddroid.EmbeddedPrivilegedClient
import io.github.arttvad9r.typeddroid.EmbeddedPrivilegedEndpoint
import io.github.arttvad9r.typeddroid.EmbeddedPrivilegedStore
import java.security.SecureRandom

internal data class EmbeddedAdbBootstrapResult(
    val endpoint: EmbeddedPrivilegedEndpoint,
    val apkPath: String,
)

/** Starts the TypedDroid-owned shell-UID helper from the installed APK. */
internal class EmbeddedAdbBootstrap(
    private val store: EmbeddedPrivilegedStore? = null,
    private val random: SecureRandom = SecureRandom(),
) {
    fun bootstrap(connection: AdbSecureConnection): EmbeddedAdbBootstrapResult {
        val shell = connection.shellExecutor()
        val apkPath = discoverBaseApk(shell)
        repeat(BOOTSTRAP_ATTEMPTS) {
            val endpoint = newEndpoint()
            val result = shell.execute(buildBootstrapCommand(apkPath, endpoint))
            check(result.exitCode == 0) {
                "Could not start embedded privileged helper: ${result.stderr.decodeToString().take(200)}"
            }
            if (waitForHelper(endpoint)) {
                store?.saveEndpoint(endpoint)
                return EmbeddedAdbBootstrapResult(endpoint, apkPath)
            }
        }
        error("Embedded privileged helper did not become reachable on loopback.")
    }

    private fun waitForHelper(endpoint: EmbeddedPrivilegedEndpoint): Boolean {
        repeat(HELPER_READY_ATTEMPTS) {
            if (EmbeddedPrivilegedClient(endpoint).ping()) return true
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

    private fun newEndpoint(): EmbeddedPrivilegedEndpoint {
        val port = MIN_LOOPBACK_PORT + random.nextInt(MAX_LOOPBACK_PORT - MIN_LOOPBACK_PORT + 1)
        val tokenBytes = ByteArray(32).also(random::nextBytes)
        return EmbeddedPrivilegedEndpoint(
            port = port,
            authToken = tokenBytes.joinToString("") { "%02x".format(it) },
        )
    }

    private companion object {
        const val BOOTSTRAP_ATTEMPTS = 4
        const val HELPER_READY_ATTEMPTS = 40
        const val HELPER_READY_DELAY_MILLIS = 50L
        const val MIN_LOOPBACK_PORT = 32_768
        const val MAX_LOOPBACK_PORT = 60_999
    }
}

internal fun buildBootstrapCommand(
    apkPath: String,
    endpoint: EmbeddedPrivilegedEndpoint,
): String {
    require(apkPath.startsWith("/data/app/") && apkPath.endsWith(".apk")) { "Invalid bootstrap APK path." }
    return buildString {
        append("CLASSPATH=")
        append(shellQuote(apkPath))
        append(" /system/bin/app_process /system/bin ")
        append("io.github.arttvad9r.typeddroid.EmbeddedPrivilegedMain")
        append(" --port ")
        append(endpoint.port)
        append(" --token ")
        append(shellQuote(endpoint.authToken))
        append(" </dev/null >/dev/null 2>&1 &")
    }
}

internal fun shellQuote(value: String): String {
    require('\u0000' !in value) { "Shell value contains NUL." }
    return "'" + value.replace("'", "'\"'\"'") + "'"
}
