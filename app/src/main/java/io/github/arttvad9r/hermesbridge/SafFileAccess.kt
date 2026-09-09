package io.github.arttvad9r.hermesbridge

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.util.Locale

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

class FileAccessNotConfiguredException : IllegalStateException(
    "No SAF directory has been granted to Hermes Bridge."
)

class FilePathNotFoundException : IllegalArgumentException(
    "The requested SAF path does not exist."
)

class FilePathNotDirectoryException : IllegalArgumentException(
    "The requested SAF path is not a directory."
)

interface SafFilesRepository {
    fun list(pathSegments: List<String>): SafDirectorySnapshot
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
        validatePathSegments(pathSegments)

        val treeUri = treeStore.treeUri() ?: throw FileAccessNotConfiguredException()
        val root = DocumentFile.fromTreeUri(appContext, treeUri)
            ?.takeIf { it.exists() && it.isDirectory }
            ?: throw FileAccessNotConfiguredException()

        var directory = root
        pathSegments.forEach { segment ->
            val child = directory.findFile(segment) ?: throw FilePathNotFoundException()
            if (!child.isDirectory) throw FilePathNotDirectoryException()
            directory = child
        }

        val entries = directory.listFiles()
            .asSequence()
            .mapNotNull { file ->
                val name = file.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                SafFileEntrySnapshot(
                    name = name,
                    pathSegments = pathSegments + name,
                    directory = file.isDirectory,
                    mimeType = file.type,
                    sizeBytes = if (file.isDirectory) null else file.length().takeIf { it >= 0L },
                    lastModifiedEpochMillis = file.lastModified().takeIf { it > 0L },
                )
            }
            .sortedWith(
                compareByDescending<SafFileEntrySnapshot> { it.directory }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                    .thenBy { it.name.lowercase(Locale.ROOT) }
            )
            .take(MAX_ENTRIES)
            .toList()

        return SafDirectorySnapshot(
            rootName = root.name ?: "Selected folder",
            pathSegments = pathSegments,
            entries = entries,
        )
    }

    companion object {
        const val MAX_ENTRIES = 500
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
