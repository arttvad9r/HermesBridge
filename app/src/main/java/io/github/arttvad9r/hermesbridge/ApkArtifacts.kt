package io.github.arttvad9r.hermesbridge

import android.content.Context
import android.content.pm.PackageManager
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

const val MAX_APK_ARTIFACT_BYTES = 200L * 1024L * 1024L

data class ApkArtifactDescriptor(
    val artifactId: String,
    val downloadToken: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
)

data class VerifiedApkArtifact(
    val file: File,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
)

data class ApkPackageMetadata(
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val signerSha256: List<String>,
)

interface ApkArtifactRepository {
    suspend fun download(descriptor: ApkArtifactDescriptor): VerifiedApkArtifact
}

interface ApkPackageInspector {
    fun inspect(artifact: VerifiedApkArtifact): ApkPackageMetadata
}

class ApkArtifactDownloadException(message: String) : IllegalStateException(message)
class ApkInspectionException(message: String) : IllegalStateException(message)

class RelayApkArtifactRepository(
    context: Context,
    relayWebSocketUrl: String,
) : ApkArtifactRepository {
    private val appContext = context.applicationContext
    private val artifactBaseUrl = artifactBaseUrlFromWebSocket(relayWebSocketUrl)
    private val cacheDirectory = File(appContext.cacheDir, "relay-apks")

    override suspend fun download(descriptor: ApkArtifactDescriptor): VerifiedApkArtifact =
        withContext(Dispatchers.IO) {
            validateDescriptor(descriptor)
            cacheDirectory.mkdirs()

            val target = File(cacheDirectory, "${descriptor.sha256}.apk")
            if (target.isFile && target.length() == descriptor.sizeBytes) {
                val existingHash = sha256(target)
                if (existingHash == descriptor.sha256) {
                    return@withContext VerifiedApkArtifact(
                        file = target,
                        fileName = descriptor.fileName,
                        sizeBytes = descriptor.sizeBytes,
                        sha256 = descriptor.sha256,
                    )
                }
                runCatching { target.delete() }
            }

            val temp = File.createTempFile("apk-", ".part", cacheDirectory)
            try {
                val url = URL("$artifactBaseUrl/${descriptor.artifactId}")
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = CONNECT_TIMEOUT_MILLIS
                    readTimeout = READ_TIMEOUT_MILLIS
                    requestMethod = "GET"
                    setRequestProperty("Authorization", "Bearer ${descriptor.downloadToken}")
                    setRequestProperty("Accept", "application/vnd.android.package-archive")
                    setRequestProperty("Cache-Control", "no-store")
                }

                try {
                    val status = connection.responseCode
                    if (status != HttpURLConnection.HTTP_OK) {
                        throw ApkArtifactDownloadException("Relay APK download returned HTTP $status.")
                    }
                    val contentLength = connection.contentLengthLong
                    if (contentLength >= 0 && contentLength != descriptor.sizeBytes) {
                        throw ApkArtifactDownloadException("Relay APK size does not match the staged artifact metadata.")
                    }

                    val digest = MessageDigest.getInstance("SHA-256")
                    var total = 0L
                    connection.inputStream.use { input ->
                        temp.outputStream().buffered().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                coroutineContext.ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                if (read == 0) continue
                                total += read
                                if (total > descriptor.sizeBytes || total > MAX_APK_ARTIFACT_BYTES) {
                                    throw ApkArtifactDownloadException("Relay APK exceeded its declared size.")
                                }
                                digest.update(buffer, 0, read)
                                output.write(buffer, 0, read)
                            }
                        }
                    }

                    if (total != descriptor.sizeBytes) {
                        throw ApkArtifactDownloadException("Relay APK ended before the declared size.")
                    }
                    val actualHash = digest.digest().toHex()
                    if (actualHash != descriptor.sha256) {
                        throw ApkArtifactDownloadException("Relay APK SHA-256 verification failed.")
                    }
                } finally {
                    connection.disconnect()
                }

                if (target.exists() && !target.delete()) {
                    throw ApkArtifactDownloadException("Could not replace the cached verified APK.")
                }
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    if (!temp.delete()) temp.deleteOnExit()
                }
                VerifiedApkArtifact(
                    file = target,
                    fileName = descriptor.fileName,
                    sizeBytes = descriptor.sizeBytes,
                    sha256 = descriptor.sha256,
                )
            } catch (error: Throwable) {
                runCatching { temp.delete() }
                throw error
            }
        }

    companion object {
        private const val CONNECT_TIMEOUT_MILLIS = 15_000
        private const val READ_TIMEOUT_MILLIS = 60_000
        private val ARTIFACT_ID_REGEX = Regex("^apk_[0-9a-fA-F-]{36}$")
        private val TOKEN_REGEX = Regex("^[A-Za-z0-9_-]{40,128}$")
        private val SHA256_REGEX = Regex("^[0-9a-f]{64}$")

        internal fun artifactBaseUrlFromWebSocket(relayWebSocketUrl: String): String =
            parseRelayEndpoint(relayWebSocketUrl).artifactBaseUrl

        fun validateDescriptor(descriptor: ApkArtifactDescriptor) {
            require(ARTIFACT_ID_REGEX.matches(descriptor.artifactId)) { "Invalid APK artifact ID." }
            require(TOKEN_REGEX.matches(descriptor.downloadToken)) { "Invalid APK artifact token." }
            require(descriptor.fileName.length in 5..120) { "Invalid APK file name length." }
            require(descriptor.fileName.endsWith(".apk", ignoreCase = true)) { "Artifact is not an APK." }
            require(descriptor.fileName.none { it.isISOControl() || it == '/' || it == '\\' }) {
                "Invalid APK file name."
            }
            require(descriptor.sizeBytes in 1..MAX_APK_ARTIFACT_BYTES) { "Invalid APK artifact size." }
            require(SHA256_REGEX.matches(descriptor.sha256)) { "Invalid APK SHA-256." }
        }

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) digest.update(buffer, 0, read)
                }
            }
            return digest.digest().toHex()
        }
    }
}

class AndroidApkPackageInspector(
    context: Context,
) : ApkPackageInspector {
    private val packageManager = context.applicationContext.packageManager

    override fun inspect(artifact: VerifiedApkArtifact): ApkPackageMetadata {
        val packageInfo = packageManager.getPackageArchiveInfo(
            artifact.file.absolutePath,
            PackageManager.GET_SIGNING_CERTIFICATES,
        ) ?: throw ApkInspectionException("Android could not parse the staged APK.")

        val signingInfo = packageInfo.signingInfo
            ?: throw ApkInspectionException("APK has no signing information.")
        val signers = signingInfo.apkContentsSigners
            ?.map { signer -> MessageDigest.getInstance("SHA-256").digest(signer.toByteArray()).toHex() }
            ?.distinct()
            ?.sorted()
            .orEmpty()
        if (signers.isEmpty()) {
            throw ApkInspectionException("APK signing certificates could not be read.")
        }

        return ApkPackageMetadata(
            packageName = packageInfo.packageName,
            versionName = packageInfo.versionName,
            versionCode = packageInfo.longVersionCode,
            signerSha256 = signers,
        )
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
