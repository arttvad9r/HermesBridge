package io.github.arttvad9r.typeddroid.relay

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable

@Serializable
data class StagedFileResponse(
    val artifactId: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val downloadToken: String,
    val expiresAtEpochMillis: Long,
)

@Serializable
data class FileUploadSlotResponse(
    val uploadId: String,
    val uploadToken: String,
    val expiresAtEpochMillis: Long,
)

@Serializable
data class FileUploadStatusResponse(
    val uploadId: String,
    val complete: Boolean,
    val fileName: String? = null,
    val sizeBytes: Long? = null,
    val sha256: String? = null,
    val expiresAtEpochMillis: Long,
)

data class GenericFileArtifact(
    val artifactId: String,
    val fileName: String,
    val path: Path,
    val sizeBytes: Long,
    val sha256: String,
    val downloadToken: String,
    val expiresAtEpochMillis: Long,
)

data class CompletedFileUpload(
    val uploadId: String,
    val fileName: String,
    val path: Path,
    val sizeBytes: Long,
    val sha256: String,
    val expiresAtEpochMillis: Long,
)

private data class FileUploadSlot(
    val uploadId: String,
    val uploadToken: String,
    val expiresAtEpochMillis: Long,
    var uploading: Boolean = false,
    var completed: CompletedFileUpload? = null,
)

class InvalidFileTransferException(message: String) : IllegalArgumentException(message)
class FileTransferTooLargeException(message: String) : IllegalArgumentException(message)
class FileTransferNotFoundException(message: String) : IllegalStateException(message)
class FileTransferConflictException(message: String) : IllegalStateException(message)

class FileTransferStore(
    rootDirectory: Path? = null,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    private val root = (rootDirectory ?: Files.createTempDirectory("typed-droid-file-transfers"))
        .toAbsolutePath()
        .normalize()
    private val artifacts = ConcurrentHashMap<String, GenericFileArtifact>()
    private val uploadSlots = ConcurrentHashMap<String, FileUploadSlot>()
    private val lock = Any()

    init {
        Files.createDirectories(root)
        cleanupOrphansAtStartup()
    }

    fun stageDownload(
        fileName: String,
        input: InputStream,
        declaredLength: Long? = null,
        ttlMillis: Long = DEFAULT_TTL_MILLIS,
    ): StagedFileResponse {
        val safeName = validateFileName(fileName)
        validateLength(declaredLength)
        validateTtl(ttlMillis)
        cleanupExpired()
        val temp = Files.createTempFile(root, ".download-upload-", ".tmp")
        var persisted: Path? = null
        try {
            val copied = copyVerified(input, temp, declaredLength)
            val artifactId = "file_${UUID.randomUUID()}"
            val finalPath = root.resolve("$artifactId.bin").normalize()
            check(finalPath.parent == root)
            moveAtomically(temp, finalPath)
            persisted = finalPath
            val artifact = GenericFileArtifact(
                artifactId = artifactId,
                fileName = safeName,
                path = finalPath,
                sizeBytes = copied.sizeBytes,
                sha256 = copied.sha256,
                downloadToken = newToken(),
                expiresAtEpochMillis = nowMillis() + ttlMillis,
            )
            artifacts[artifactId] = artifact
            return artifact.toResponse()
        } catch (error: Throwable) {
            runCatching { Files.deleteIfExists(temp) }
            persisted?.let { runCatching { Files.deleteIfExists(it) } }
            throw error
        }
    }

    fun findDownloadAuthorized(artifactId: String, token: String): GenericFileArtifact? {
        cleanupExpired()
        val artifact = artifacts[artifactId] ?: return null
        val expected = artifact.downloadToken.toByteArray(Charsets.UTF_8)
        val supplied = token.toByteArray(Charsets.UTF_8)
        return artifact.takeIf {
            MessageDigest.isEqual(expected, supplied) && Files.isRegularFile(it.path, LinkOption.NOFOLLOW_LINKS)
        }
    }

    fun createUploadSlot(ttlMillis: Long = DEFAULT_TTL_MILLIS): FileUploadSlotResponse {
        validateTtl(ttlMillis)
        cleanupExpired()
        synchronized(lock) {
            if (uploadSlots.size >= MAX_UPLOAD_SLOTS) {
                cleanupExpiredLocked()
                if (uploadSlots.size >= MAX_UPLOAD_SLOTS) {
                    throw FileTransferConflictException("Too many live upload slots.")
                }
            }
            val slot = FileUploadSlot(
                uploadId = "upload_${UUID.randomUUID()}",
                uploadToken = newToken(),
                expiresAtEpochMillis = nowMillis() + ttlMillis,
            )
            uploadSlots[slot.uploadId] = slot
            return FileUploadSlotResponse(slot.uploadId, slot.uploadToken, slot.expiresAtEpochMillis)
        }
    }

    fun receiveUpload(
        uploadId: String,
        token: String,
        fileName: String,
        input: InputStream,
        declaredLength: Long? = null,
    ): FileUploadStatusResponse {
        val safeName = validateFileName(fileName)
        validateLength(declaredLength)
        val slot = synchronized(lock) {
            cleanupExpiredLocked()
            val found = uploadSlots[uploadId]
                ?: throw FileTransferNotFoundException("Upload slot is missing or expired.")
            val expected = found.uploadToken.toByteArray(Charsets.UTF_8)
            val supplied = token.toByteArray(Charsets.UTF_8)
            if (!MessageDigest.isEqual(expected, supplied)) {
                throw FileTransferNotFoundException("Upload slot is missing or expired.")
            }
            if (found.completed != null || found.uploading) {
                throw FileTransferConflictException("Upload slot has already been used.")
            }
            found.uploading = true
            found
        }
        val temp = Files.createTempFile(root, ".device-upload-", ".tmp")
        var finalPath: Path? = null
        try {
            val copied = copyVerified(input, temp, declaredLength)
            val target = root.resolve("${slot.uploadId}.bin").normalize()
            check(target.parent == root)
            moveAtomically(temp, target)
            finalPath = target
            val completed = CompletedFileUpload(
                uploadId = slot.uploadId,
                fileName = safeName,
                path = target,
                sizeBytes = copied.sizeBytes,
                sha256 = copied.sha256,
                expiresAtEpochMillis = slot.expiresAtEpochMillis,
            )
            synchronized(lock) {
                val current = uploadSlots[slot.uploadId]
                if (current !== slot || current.expiresAtEpochMillis <= nowMillis()) {
                    throw FileTransferNotFoundException("Upload slot expired during transfer.")
                }
                current.completed = completed
                current.uploading = false
            }
            return completed.toStatus()
        } catch (error: Throwable) {
            runCatching { Files.deleteIfExists(temp) }
            finalPath?.let { runCatching { Files.deleteIfExists(it) } }
            synchronized(lock) {
                uploadSlots[slot.uploadId]?.takeIf { it === slot }?.uploading = false
            }
            throw error
        }
    }

    fun uploadStatus(uploadId: String): FileUploadStatusResponse? = synchronized(lock) {
        cleanupExpiredLocked()
        val slot = uploadSlots[uploadId] ?: return@synchronized null
        slot.completed?.toStatus() ?: FileUploadStatusResponse(
            uploadId = slot.uploadId,
            complete = false,
            expiresAtEpochMillis = slot.expiresAtEpochMillis,
        )
    }

    fun completedUpload(uploadId: String): CompletedFileUpload? = synchronized(lock) {
        cleanupExpiredLocked()
        uploadSlots[uploadId]?.completed?.takeIf { Files.isRegularFile(it.path, LinkOption.NOFOLLOW_LINKS) }
    }

    fun cleanupExpired() = synchronized(lock) { cleanupExpiredLocked() }

    private fun cleanupExpiredLocked() {
        val now = nowMillis()
        artifacts.entries.removeIf { entry ->
            if (entry.value.expiresAtEpochMillis > now) return@removeIf false
            runCatching { Files.deleteIfExists(entry.value.path) }
            true
        }
        uploadSlots.entries.removeIf { entry ->
            if (entry.value.expiresAtEpochMillis > now) return@removeIf false
            entry.value.completed?.let { runCatching { Files.deleteIfExists(it.path) } }
            true
        }
    }

    private fun cleanupOrphansAtStartup() {
        Files.newDirectoryStream(root).use { entries ->
            for (path in entries) {
                val name = path.fileName.toString()
                if (!OWNED_FILE_REGEX.matches(name)) continue
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue
                Files.deleteIfExists(path)
            }
        }
    }

    private data class CopiedFile(val sizeBytes: Long, val sha256: String)

    private fun copyVerified(input: InputStream, target: Path, declaredLength: Long?): CopiedFile {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        Files.newOutputStream(target).buffered().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                total += read
                if (total > MAX_FILE_BYTES) {
                    throw FileTransferTooLargeException("File exceeds the $MAX_FILE_BYTES byte transfer limit.")
                }
                digest.update(buffer, 0, read)
                output.write(buffer, 0, read)
            }
        }
        if (total <= 0L) throw InvalidFileTransferException("Transferred file is empty.")
        if (declaredLength != null && total != declaredLength) {
            throw InvalidFileTransferException("Body length did not match Content-Length.")
        }
        return CopiedFile(total, digest.digest().joinToString("") { "%02x".format(it) })
    }

    private fun validateFileName(fileName: String): String {
        val value = fileName.trim()
        if (value.length !in 1..MAX_FILE_NAME_LENGTH || value.any { it.isISOControl() || it == '/' || it == '\\' }) {
            throw InvalidFileTransferException("File name is invalid.")
        }
        return value
    }

    private fun validateLength(length: Long?) {
        if (length != null && length !in 1..MAX_FILE_BYTES) {
            throw FileTransferTooLargeException("Content-Length is outside the transfer limit.")
        }
    }

    private fun validateTtl(ttlMillis: Long) {
        require(ttlMillis in MIN_TTL_MILLIS..MAX_TTL_MILLIS) { "Invalid transfer TTL." }
    }

    private fun newToken(): String = ByteArray(32).also(random::nextBytes).let {
        Base64.getUrlEncoder().withoutPadding().encodeToString(it)
    }

    private fun moveAtomically(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun GenericFileArtifact.toResponse() = StagedFileResponse(
        artifactId, fileName, sizeBytes, sha256, downloadToken, expiresAtEpochMillis,
    )

    private fun CompletedFileUpload.toStatus() = FileUploadStatusResponse(
        uploadId = uploadId,
        complete = true,
        fileName = fileName,
        sizeBytes = sizeBytes,
        sha256 = sha256,
        expiresAtEpochMillis = expiresAtEpochMillis,
    )

    companion object {
        const val MAX_FILE_BYTES = 512L * 1024L * 1024L
        private const val MAX_FILE_NAME_LENGTH = 120
        private const val DEFAULT_TTL_MILLIS = 15L * 60L * 1000L
        private const val MIN_TTL_MILLIS = 1_000L
        private const val MAX_TTL_MILLIS = 60L * 60L * 1000L
        private const val MAX_UPLOAD_SLOTS = 200
        private val OWNED_FILE_REGEX = Regex(
            "^(?:file_[0-9a-fA-F-]{36}|upload_[0-9a-fA-F-]{36})\\.bin$|^\\.(?:download-upload|device-upload)-.+\\.tmp$"
        )
    }
}
