package io.github.arttvad9r.hermesbridge.relay

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable

@Serializable
data class StagedApkResponse(
    val artifactId: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val downloadToken: String,
    val expiresAtEpochMillis: Long,
)

data class ApkArtifact(
    val artifactId: String,
    val fileName: String,
    val path: Path,
    val sizeBytes: Long,
    val sha256: String,
    val downloadToken: String,
    val expiresAtEpochMillis: Long,
)

private data class ApkContentKey(
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
)

private data class ApkStagingBinding(
    val contentKey: ApkContentKey,
    val artifactId: String,
)

class InvalidApkArtifactException(message: String) : IllegalArgumentException(message)
class ApkArtifactTooLargeException(message: String) : IllegalArgumentException(message)
class ApkStagingConflictException(message: String) : IllegalArgumentException(message)
class ApkStagingExpiredException(message: String) : IllegalStateException(message)
class ApkStagingBusyException(message: String) : IllegalStateException(message)

class ApkArtifactStore(
    rootDirectory: Path? = null,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    private val root = (rootDirectory ?: Files.createTempDirectory("hermes-bridge-apks"))
        .toAbsolutePath()
        .normalize()
    private val artifacts = ConcurrentHashMap<String, ApkArtifact>()
    private val stagingBindings = LinkedHashMap<String, ApkStagingBinding>()
    private val stageLock = Any()

    init {
        Files.createDirectories(root)
    }

    fun stage(
        fileName: String,
        input: InputStream,
        declaredLength: Long? = null,
        ttlMillis: Long = DEFAULT_TTL_MILLIS,
        stagingKey: String? = null,
    ): StagedApkResponse {
        val safeName = validateFileName(fileName)
        if (declaredLength != null && declaredLength !in 1..MAX_APK_BYTES) {
            throw ApkArtifactTooLargeException(
                "APK Content-Length must be between 1 and $MAX_APK_BYTES bytes."
            )
        }
        require(ttlMillis in 1_000L..MAX_TTL_MILLIS) { "Invalid artifact TTL." }
        cleanupExpired()

        val tempPath = Files.createTempFile(root, ".upload-", ".tmp")
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        var persistedPath: Path? = null

        try {
            Files.newOutputStream(tempPath).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    total += read
                    if (total > MAX_APK_BYTES) {
                        throw ApkArtifactTooLargeException(
                            "APK exceeds the $MAX_APK_BYTES byte staging limit."
                        )
                    }
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                }
            }

            if (total == 0L) {
                throw InvalidApkArtifactException("APK upload is empty.")
            }
            if (declaredLength != null && total != declaredLength) {
                throw InvalidApkArtifactException(
                    "APK body length did not match Content-Length."
                )
            }

            val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
            val contentKey = ApkContentKey(safeName, total, sha256)

            return synchronized(stageLock) {
                cleanupExpiredLocked()

                if (stagingKey != null) {
                    val binding = stagingBindings[stagingKey]
                    if (binding != null) {
                        val existing = artifacts[binding.artifactId]
                        if (existing == null || !Files.isRegularFile(existing.path)) {
                            throw ApkStagingExpiredException(
                                "APK staging key refers to an expired artifact."
                            )
                        }
                        if (binding.contentKey != contentKey) {
                            throw ApkStagingConflictException(
                                "APK staging key is already bound to different verified content."
                            )
                        }
                        Files.deleteIfExists(tempPath)
                        return@synchronized existing.toResponse()
                    }
                    if (!makeRoomForStagingBindingLocked()) {
                        throw ApkStagingBusyException(
                            "Too many live APK staging identities are retained."
                        )
                    }
                }

                val artifactId = "apk_${UUID.randomUUID()}"
                val finalPath = root.resolve("$artifactId.apk").normalize()
                check(finalPath.parent == root) { "Artifact path escaped its root." }
                try {
                    Files.move(
                        tempPath,
                        finalPath,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: Exception) {
                    Files.move(tempPath, finalPath, StandardCopyOption.REPLACE_EXISTING)
                }
                persistedPath = finalPath

                val tokenBytes = ByteArray(32).also(random::nextBytes)
                val artifact = ApkArtifact(
                    artifactId = artifactId,
                    fileName = safeName,
                    path = finalPath,
                    sizeBytes = total,
                    sha256 = sha256,
                    downloadToken = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes),
                    expiresAtEpochMillis = nowMillis() + ttlMillis,
                )
                artifacts[artifactId] = artifact
                if (stagingKey != null) {
                    stagingBindings[stagingKey] = ApkStagingBinding(contentKey, artifactId)
                }
                artifact.toResponse()
            }
        } catch (error: Throwable) {
            runCatching { Files.deleteIfExists(tempPath) }
            persistedPath?.let { path -> runCatching { Files.deleteIfExists(path) } }
            throw error
        }
    }

    fun findAuthorized(artifactId: String, token: String): ApkArtifact? = synchronized(stageLock) {
        cleanupExpiredLocked()
        val artifact = artifacts[artifactId] ?: return@synchronized null
        val expected = artifact.downloadToken.toByteArray(Charsets.UTF_8)
        val supplied = token.toByteArray(Charsets.UTF_8)
        artifact.takeIf { MessageDigest.isEqual(expected, supplied) && Files.isRegularFile(it.path) }
    }

    fun cleanupExpired() = synchronized(stageLock) {
        cleanupExpiredLocked()
    }

    private fun cleanupExpiredLocked() {
        val now = nowMillis()
        artifacts.entries.removeIf { entry ->
            if (entry.value.expiresAtEpochMillis > now) return@removeIf false
            runCatching { Files.deleteIfExists(entry.value.path) }
            true
        }
    }

    private fun makeRoomForStagingBindingLocked(): Boolean {
        if (stagingBindings.size < MAX_STAGING_BINDINGS) return true
        val removable = stagingBindings.entries.firstOrNull { entry ->
            !artifacts.containsKey(entry.value.artifactId)
        } ?: return false
        stagingBindings.remove(removable.key)
        return true
    }

    private fun validateFileName(fileName: String): String {
        val value = fileName.trim()
        if (value.length !in 5..MAX_FILE_NAME_LENGTH) {
            throw InvalidApkArtifactException("APK file name has an invalid length.")
        }
        if (!value.endsWith(".apk", ignoreCase = true)) {
            throw InvalidApkArtifactException("Only .apk artifacts are accepted.")
        }
        if (value.any { it.isISOControl() } || '/' in value || '\\' in value) {
            throw InvalidApkArtifactException("APK file name contains forbidden characters.")
        }
        return value
    }

    private fun ApkArtifact.toResponse() = StagedApkResponse(
        artifactId = artifactId,
        fileName = fileName,
        sizeBytes = sizeBytes,
        sha256 = sha256,
        downloadToken = downloadToken,
        expiresAtEpochMillis = expiresAtEpochMillis,
    )

    companion object {
        const val MAX_APK_BYTES = 200L * 1024L * 1024L
        private const val MAX_FILE_NAME_LENGTH = 120
        private const val DEFAULT_TTL_MILLIS = 10L * 60L * 1000L
        private const val MAX_TTL_MILLIS = 60L * 60L * 1000L
        private const val MAX_STAGING_BINDINGS = 200
    }
}
