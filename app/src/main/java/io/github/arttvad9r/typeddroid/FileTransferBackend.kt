package io.github.arttvad9r.typeddroid

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Paths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString

data class FileTransferDescriptor(
    val artifactId: String,
    val downloadToken: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
)

data class FileTransferResult(
    val ok: Boolean,
    val fileName: String? = null,
    val sizeBytes: Long? = null,
    val sha256: String? = null,
    val code: String? = null,
    val message: String? = null,
)

interface FileTransferBackend {
    fun readiness(): PrivilegedBackendReadiness
    suspend fun pushToDevice(
        descriptor: FileTransferDescriptor,
        destinationPath: String,
        overwrite: Boolean,
        createParents: Boolean,
    ): FileTransferResult
    suspend fun pullFromDevice(
        sourcePath: String,
        uploadId: String,
        uploadToken: String,
    ): FileTransferResult
}

internal object DisabledFileTransferBackend : FileTransferBackend {
    override fun readiness() = PrivilegedBackendReadiness(
        false,
        "file_transfer_unavailable",
        "File transfer backend is unavailable.",
    )

    override suspend fun pushToDevice(
        descriptor: FileTransferDescriptor,
        destinationPath: String,
        overwrite: Boolean,
        createParents: Boolean,
    ) = FileTransferResult(false, code = "file_transfer_unavailable", message = "File transfer backend is unavailable.")

    override suspend fun pullFromDevice(sourcePath: String, uploadId: String, uploadToken: String) =
        FileTransferResult(false, code = "file_transfer_unavailable", message = "File transfer backend is unavailable.")
}

internal class RelayFileTransferBackend(
    context: Context,
    private val endpointProvider: () -> RelayConnectionEndpoint,
) : FileTransferBackend {
    constructor(context: Context, relayWebSocketUrl: String) : this(
        context,
        { RelayConnectionEndpoint(parseRelayEndpoint(relayWebSocketUrl)) },
    )

    private val store = EmbeddedPrivilegedStore(context.applicationContext)

    override fun readiness(): PrivilegedBackendReadiness {
        val endpoint = store.endpoint()
            ?: return PrivilegedBackendReadiness(false, "embedded_privileged_unavailable", "Enable full device access before transferring files.")
        return if (EmbeddedPrivilegedClient(endpoint).ping()) {
            PrivilegedBackendReadiness(true)
        } else {
            runCatching(store::clear)
            PrivilegedBackendReadiness(false, "embedded_privileged_unavailable", "Restore full device access before transferring files.")
        }
    }

    override suspend fun pushToDevice(
        descriptor: FileTransferDescriptor,
        destinationPath: String,
        overwrite: Boolean,
        createParents: Boolean,
    ): FileTransferResult = withContext(Dispatchers.IO) {
        try {
            validateDescriptor(descriptor)
            val endpoint = requireNotNull(store.endpoint()) { "Embedded privileged helper is unavailable." }
            val relayEndpoint = endpointProvider()
            val url = URL("${relayEndpoint.endpoint.fileArtifactBaseUrl}/${descriptor.artifactId}")
            val connection = openRelayHttpConnection(url, relayEndpoint).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = TRANSFER_TIMEOUT_MILLIS
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer ${descriptor.downloadToken}")
                setRequestProperty("Accept", "application/octet-stream")
                setRequestProperty("Cache-Control", "no-store")
            }
            try {
                val status = connection.responseCode
                if (status != HttpURLConnection.HTTP_OK) error("Relay file download returned HTTP $status.")
                val contentLength = connection.contentLengthLong
                if (contentLength >= 0 && contentLength != descriptor.sizeBytes) error("Relay file size did not match staged metadata.")
                connection.inputStream.use { input ->
                    val result = EmbeddedPrivilegedClient(endpoint).writeFileStream(
                        filePath = destinationPath,
                        sizeBytes = descriptor.sizeBytes,
                        sha256 = descriptor.sha256,
                        overwrite = overwrite,
                        createParents = createParents,
                        input = input,
                    )
                    if (result.exitCode != 0) error("Privileged file writer returned status ${result.exitCode}.")
                }
            } finally {
                connection.disconnect()
            }
            FileTransferResult(true, descriptor.fileName, descriptor.sizeBytes, descriptor.sha256)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            FileTransferResult(false, code = "file_push_failed", message = safeTransferError(error))
        }
    }

    override suspend fun pullFromDevice(
        sourcePath: String,
        uploadId: String,
        uploadToken: String,
    ): FileTransferResult = withContext(Dispatchers.IO) {
        try {
            require(UPLOAD_ID_REGEX.matches(uploadId)) { "Upload slot ID is invalid." }
            require(TOKEN_REGEX.matches(uploadToken)) { "Upload token is invalid." }
            val endpoint = requireNotNull(store.endpoint()) { "Embedded privileged helper is unavailable." }
            EmbeddedPrivilegedClient(endpoint).withFileInputStream(sourcePath) { sizeBytes, input ->
                val relayEndpoint = endpointProvider()
                val url = URL("${relayEndpoint.endpoint.deviceUploadBaseUrl}/$uploadId")
                val connection = openRelayHttpConnection(url, relayEndpoint).apply {
                    instanceFollowRedirects = false
                    connectTimeout = CONNECT_TIMEOUT_MILLIS
                    readTimeout = TRANSFER_TIMEOUT_MILLIS
                    requestMethod = "POST"
                    doOutput = true
                    setFixedLengthStreamingMode(sizeBytes)
                    setRequestProperty("Authorization", "Bearer $uploadToken")
                    setRequestProperty(FILE_NAME_HEADER, safeFileName(sourcePath))
                    setRequestProperty("Content-Type", "application/octet-stream")
                    setRequestProperty("Cache-Control", "no-store")
                }
                try {
                    connection.outputStream.buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var remaining = sizeBytes
                        while (remaining > 0) {
                            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            check(read >= 0) { "Privileged file stream ended early." }
                            if (read == 0) continue
                            output.write(buffer, 0, read)
                            remaining -= read
                        }
                    }
                    val status = connection.responseCode
                    if (status != HttpURLConnection.HTTP_CREATED) error("Relay file upload returned HTTP $status.")
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val uploaded = EmbeddedPrivilegedProtocol.json.decodeFromString<RelayUploadResponse>(response)
                    check(uploaded.complete && uploaded.uploadId == uploadId) { "Relay returned invalid upload metadata." }
                    check(uploaded.sizeBytes == sizeBytes && uploaded.sha256?.matches(SHA256_REGEX) == true) {
                        "Relay upload verification metadata is invalid."
                    }
                    FileTransferResult(true, uploaded.fileName, uploaded.sizeBytes, uploaded.sha256)
                } finally {
                    connection.disconnect()
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            FileTransferResult(false, code = "file_pull_failed", message = safeTransferError(error))
        }
    }

    private fun validateDescriptor(descriptor: FileTransferDescriptor) {
        require(ARTIFACT_ID_REGEX.matches(descriptor.artifactId)) { "File artifact ID is invalid." }
        require(TOKEN_REGEX.matches(descriptor.downloadToken)) { "File artifact token is invalid." }
        require(descriptor.fileName.length in 1..120 && descriptor.fileName.none { it.isISOControl() || it == '/' || it == '\\' }) {
            "File artifact name is invalid."
        }
        require(descriptor.sizeBytes in 1..EmbeddedPrivilegedProtocol.MAX_TRANSFER_FILE_BYTES) { "File artifact size is invalid." }
        require(SHA256_REGEX.matches(descriptor.sha256)) { "File artifact SHA-256 is invalid." }
    }

    private fun safeFileName(path: String): String {
        val raw = runCatching { Paths.get(path).fileName?.toString() }.getOrNull().orEmpty()
        val cleaned = raw.map { if (it.isISOControl() || it == '/' || it == '\\') '_' else it }.joinToString("").take(120)
        return cleaned.ifBlank { "device-file.bin" }
    }

    private fun safeTransferError(error: Throwable): String =
        ((error.cause ?: error).message ?: error::class.java.simpleName).take(300)

    @Serializable
    private data class RelayUploadResponse(
        val uploadId: String,
        val complete: Boolean,
        val fileName: String? = null,
        val sizeBytes: Long? = null,
        val sha256: String? = null,
        val expiresAtEpochMillis: Long,
    )

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val TRANSFER_TIMEOUT_MILLIS = 10 * 60 * 1000
        const val FILE_NAME_HEADER = "X-TypedDroid-File-Name"
        val ARTIFACT_ID_REGEX = Regex("^file_[0-9a-fA-F-]{36}$")
        val UPLOAD_ID_REGEX = Regex("^upload_[0-9a-fA-F-]{36}$")
        val TOKEN_REGEX = Regex("^[A-Za-z0-9_-]{40,128}$")
        val SHA256_REGEX = Regex("^[0-9a-f]{64}$")
    }
}
