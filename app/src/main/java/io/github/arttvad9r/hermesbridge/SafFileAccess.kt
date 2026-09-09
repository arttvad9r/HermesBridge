package io.github.arttvad9r.hermesbridge

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.util.ArrayDeque
import java.util.Locale
import java.util.PriorityQueue

data class SafFileEntrySnapshot(
    val name: String,
    val pathSegments: List<String>,
    val directory: Boolean,
    val mimeType: String?,
    val sizeBytes: Long?,
    val lastModifiedEpochMillis: Long?,
)

data class SafDirectorySnapshot(
    val rootName: String,
    val pathSegments: List<String>,
    val entries: List<SafFileEntrySnapshot>,
)

data class SafLargeFileSnapshot(
    val name: String,
    val pathSegments: List<String>,
    val mimeType: String?,
    val sizeBytes: Long,
    val lastModifiedEpochMillis: Long?,
)

data class SafAnalysisSnapshot(
    val rootName: String,
    val pathSegments: List<String>,
    val scannedEntries: Int,
    val fileCount: Int,
    val directoryCount: Int,
    val totalBytes: Long,
    val truncated: Boolean,
    val largestFiles: List<SafLargeFileSnapshot>,
)

data class SafTargetSnapshot(
    val name: String,
    val pathSegments: List<String>,
    val directory: Boolean,
    val mimeType: String?,
    val sizeBytes: Long?,
    val lastModifiedEpochMillis: Long?,
)

class FileAccessNotConfiguredException : IllegalStateException(
    "No SAF directory has been granted to Hermes Bridge."
)

class FilePathNotFoundException : IllegalArgumentException(
    "The requested SAF path does not exist."
)

class FilePathNotDirectoryException : IllegalArgumentException(
    "The requested SAF path is not a directory."
)

class FileDeleteFailedException : IllegalStateException(
    "The document provider refused to delete the requested SAF target."
)

interface SafFilesRepository {
    fun list(pathSegments: List<String>): SafDirectorySnapshot

    fun analyze(pathSegments: List<String>): SafAnalysisSnapshot =
        throw UnsupportedOperationException("SAF analysis is not implemented by this repository.")

    fun stat(pathSegments: List<String>): SafTargetSnapshot =
        throw UnsupportedOperationException("SAF stat is not implemented by this repository.")

    fun delete(pathSegments: List<String>) {
        throw UnsupportedOperationException("SAF deletion is not implemented by this repository.")
    }
}

class SafTreeStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "hermes_bridge_file_access",
        Context.MODE_PRIVATE,
    )

    fun treeUri(): Uri? = preferences.getString(KEY_TREE_URI, null)
        ?.takeIf { it.isNotBlank() }
        ?.let(Uri::parse)

    fun save(treeUri: Uri) {
        require(treeUri.scheme == "content")
        preferences.edit().putString(KEY_TREE_URI, treeUri.toString()).apply()
    }

    fun clear() {
        preferences.edit().remove(KEY_TREE_URI).apply()
    }

    private companion object {
        const val KEY_TREE_URI = "tree_uri"
    }
}

class AndroidSafFilesRepository(
    context: Context,
    private val treeStore: SafTreeStore = SafTreeStore(context),
) : SafFilesRepository {
    private val appContext = context.applicationContext

    override fun list(pathSegments: List<String>): SafDirectorySnapshot {
        val directory = resolveDirectory(pathSegments)
        val entries = directory.listFiles()
            .asSequence()
            .mapNotNull { file -> entrySnapshot(file, pathSegments) }
            .sortedWith(
                compareByDescending<SafFileEntrySnapshot> { it.directory }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                    .thenBy { it.name.lowercase(Locale.ROOT) }
            )
            .take(MAX_ENTRIES)
            .toList()

        return SafDirectorySnapshot(
            rootName = root().name ?: "Selected folder",
            pathSegments = pathSegments,
            entries = entries,
        )
    }

    override fun analyze(pathSegments: List<String>): SafAnalysisSnapshot {
        val start = resolveDirectory(pathSegments)
        val queue = ArrayDeque<Pair<DocumentFile, List<String>>>()
        queue.add(start to pathSegments)
        val largest = PriorityQueue<SafLargeFileSnapshot>(
            compareBy<SafLargeFileSnapshot> { it.sizeBytes }
                .thenBy { it.name.lowercase(Locale.ROOT) }
        )

        var scanned = 0
        var files = 0
        var directories = 0
        var totalBytes = 0L
        var truncated = false

        while (queue.isNotEmpty() && scanned < MAX_ANALYSIS_ENTRIES) {
            val (directory, directoryPath) = queue.removeFirst()
            for (child in directory.listFiles()) {
                if (scanned >= MAX_ANALYSIS_ENTRIES) {
                    truncated = true
                    break
                }
                scanned += 1
                val name = child.name?.takeIf { it.isNotBlank() } ?: continue
                val childPath = directoryPath + name

                if (child.isDirectory) {
                    directories += 1
                    if (childPath.size < MAX_DEPTH) {
                        queue.addLast(child to childPath)
                    } else if (child.listFiles().isNotEmpty()) {
                        truncated = true
                    }
                    continue
                }

                files += 1
                val size = child.length().coerceAtLeast(0L)
                totalBytes = saturatingAdd(totalBytes, size)
                val candidate = SafLargeFileSnapshot(
                    name = name,
                    pathSegments = childPath,
                    mimeType = child.type,
                    sizeBytes = size,
                    lastModifiedEpochMillis = child.lastModified().takeIf { it > 0L },
                )
                largest.add(candidate)
                if (largest.size > MAX_LARGEST_FILES) largest.poll()
            }
        }
        if (queue.isNotEmpty()) truncated = true

        return SafAnalysisSnapshot(
            rootName = root().name ?: "Selected folder",
            pathSegments = pathSegments,
            scannedEntries = scanned,
            fileCount = files,
            directoryCount = directories,
            totalBytes = totalBytes,
            truncated = truncated,
            largestFiles = largest.toList().sortedWith(
                compareByDescending<SafLargeFileSnapshot> { it.sizeBytes }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            ),
        )
    }

    override fun stat(pathSegments: List<String>): SafTargetSnapshot {
        require(pathSegments.isNotEmpty()) { "The granted SAF root cannot be targeted." }
        val target = resolveTarget(pathSegments)
        val name = target.name?.takeIf { it.isNotBlank() } ?: pathSegments.last()
        return SafTargetSnapshot(
            name = name,
            pathSegments = pathSegments,
            directory = target.isDirectory,
            mimeType = target.type,
            sizeBytes = if (target.isDirectory) null else target.length().takeIf { it >= 0L },
            lastModifiedEpochMillis = target.lastModified().takeIf { it > 0L },
        )
    }

    override fun delete(pathSegments: List<String>) {
        require(pathSegments.isNotEmpty()) { "The granted SAF root cannot be deleted." }
        val target = resolveTarget(pathSegments)
        if (!target.delete()) throw FileDeleteFailedException()
    }

    private fun root(): DocumentFile {
        val treeUri = treeStore.treeUri() ?: throw FileAccessNotConfiguredException()
        return DocumentFile.fromTreeUri(appContext, treeUri)
            ?.takeIf { it.exists() && it.isDirectory }
            ?: throw FileAccessNotConfiguredException()
    }

    private fun resolveDirectory(pathSegments: List<String>): DocumentFile {
        validatePathSegments(pathSegments)
        var directory = root()
        pathSegments.forEach { segment ->
            val child = directory.findFile(segment) ?: throw FilePathNotFoundException()
            if (!child.isDirectory) throw FilePathNotDirectoryException()
            directory = child
        }
        return directory
    }

    private fun resolveTarget(pathSegments: List<String>): DocumentFile {
        validatePathSegments(pathSegments)
        require(pathSegments.isNotEmpty()) { "A target below the granted root is required." }
        val parent = resolveDirectory(pathSegments.dropLast(1))
        return parent.findFile(pathSegments.last()) ?: throw FilePathNotFoundException()
    }

    private fun entrySnapshot(file: DocumentFile, parentPath: List<String>): SafFileEntrySnapshot? {
        val name = file.name?.takeIf { it.isNotBlank() } ?: return null
        return SafFileEntrySnapshot(
            name = name,
            pathSegments = parentPath + name,
            directory = file.isDirectory,
            mimeType = file.type,
            sizeBytes = if (file.isDirectory) null else file.length().takeIf { it >= 0L },
            lastModifiedEpochMillis = file.lastModified().takeIf { it > 0L },
        )
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right

    companion object {
        const val MAX_ENTRIES = 500
        const val MAX_ANALYSIS_ENTRIES = 5_000
        const val MAX_LARGEST_FILES = 50
        const val MAX_DEPTH = 32
        const val MAX_SEGMENT_LENGTH = 255

        fun validatePathSegments(pathSegments: List<String>) {
            require(pathSegments.size <= MAX_DEPTH) { "Path depth exceeds the allowed limit." }
            pathSegments.forEach { segment ->
                require(segment.isNotBlank()) { "Path segments must not be blank." }
                require(segment.length <= MAX_SEGMENT_LENGTH) { "Path segment is too long." }
                require(segment != "." && segment != "..") { "Relative path segments are not allowed." }
                require('/' !in segment && '\\' !in segment && '\u0000' !in segment) {
                    "Path segment contains a forbidden character."
                }
            }
        }
    }
}
