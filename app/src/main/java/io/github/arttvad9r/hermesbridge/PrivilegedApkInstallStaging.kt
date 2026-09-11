package io.github.arttvad9r.hermesbridge

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

internal const val SHIZUKU_APK_STAGING_DIRECTORY = "/data/local/tmp"

internal fun createShizukuApkStagingPath(id: String = UUID.randomUUID().toString()): String {
    require(UUID_REGEX.matches(id)) { "Invalid staging ID." }
    return "$SHIZUKU_APK_STAGING_DIRECTORY/hermes-bridge-$id.apk"
}

internal fun isShizukuApkStagingPath(path: String): Boolean =
    SHIZUKU_APK_STAGING_PATH_REGEX.matches(path)

internal fun isShizukuApkStagingFileName(name: String): Boolean =
    SHIZUKU_APK_STAGING_FILE_NAME_REGEX.matches(name)

/**
 * Removes only regular files that match Hermes Bridge's generated APK staging-name contract.
 *
 * Normal installs delete their staging file in `finally`, but the privileged UserService can be
 * killed between staging and cleanup. Those bytes have no recovery value after process death, so
 * a newly created service removes its own orphaned files before accepting Binder work. Unrelated
 * files and directories in `/data/local/tmp` are deliberately left untouched.
 */
internal fun cleanupOrphanedShizukuApkStagingFiles(
    directory: File = File(SHIZUKU_APK_STAGING_DIRECTORY),
): Int {
    val children = directory.listFiles() ?: return 0
    var removed = 0
    children.forEach { candidate ->
        if (!isShizukuApkStagingFileName(candidate.name)) return@forEach
        if (!candidate.isFile) return@forEach
        if (runCatching { candidate.delete() }.getOrDefault(false)) {
            removed += 1
        }
    }
    return removed
}

/**
 * Copies exactly [expectedSize] bytes across the typed Binder boundary before invoking package
 * manager. The caller has already verified the artifact hash in the app process; this second bound
 * prevents a truncated/replaced file descriptor from becoming a package-manager input.
 */
internal fun copyExactApkBytes(
    input: InputStream,
    output: OutputStream,
    expectedSize: Long,
): Long {
    require(expectedSize in 1..MAX_APK_ARTIFACT_BYTES) { "Invalid APK size." }

    val buffer = ByteArray(16 * 1024)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (read == 0) continue
        if (total + read > expectedSize) {
            throw ApkStagingSizeException("APK stream exceeded the verified size.")
        }
        output.write(buffer, 0, read)
        total += read
    }
    output.flush()

    if (total != expectedSize) {
        throw ApkStagingSizeException("APK stream ended before the verified size.")
    }
    return total
}

internal class ApkStagingSizeException(message: String) : IllegalStateException(message)

private val UUID_REGEX =
    Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
private val SHIZUKU_APK_STAGING_FILE_NAME_REGEX = Regex(
    "^hermes-bridge-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.apk$"
)
private val SHIZUKU_APK_STAGING_PATH_REGEX = Regex(
    "^/data/local/tmp/hermes-bridge-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.apk$"
)
