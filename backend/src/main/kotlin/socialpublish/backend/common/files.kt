package socialpublish.backend.common

import arrow.fx.coroutines.ExitCase
import arrow.fx.coroutines.Resource
import arrow.fx.coroutines.resource
import arrow.fx.coroutines.resourceScope
import java.io.BufferedOutputStream
import java.io.File
import java.security.MessageDigest
import java.text.Normalizer
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/**
 * Represents the source of an upload, either from a File or a Source stream.
 *
 * `FromFile` is a "cold source", meaning the client can read from it multiple times.
 * `FromSource` is a "hot source", meaning the client should read from it only once.
 */
sealed interface UploadSource {
    data class FromFile(val file: File) : UploadSource
    data class FromSource(val source: Source) : UploadSource

    /**
     * Convert the UploadSource to a Resource<File>.
     *
     * If the source is `FromFile`, it returns the existing file.
     * If the source is FromSource, it creates a temporary file
     * and saves the source to it.
     *
     * The returned Resource<File> will manage the lifecycle of the temporary file,
     * deleting it when the resource is closed.
     */
    fun asFileResource(): Resource<File> = let { uploadSource ->
        resource {
            when (uploadSource) {
                is FromFile -> uploadSource.file
                is FromSource ->
                    createTempFileResource("source-")
                        .bind()
                        .also { uploadSource.source.saveToFile(it) }
            }
        }
    }

    /**
     * Convert the UploadSource to a Resource<Source>.
     */
    fun asKotlinSource(): Resource<Source> = let { uploadSource ->
        resource {
            when (uploadSource) {
                is FromFile -> uploadSource.file.toKotlinSource().bind()
                is FromSource -> uploadSource.source
            }
        }
    }

    /**
     * Read all bytes from the UploadSource.
     */
    suspend fun readBytes(): ByteArray =
        resourceScope {
            asKotlinSource().bind().use { source ->
                source.readByteArray()
            }
        }
}

/**
 * Sanitize filename to prevent header injection attacks and path traversal.
 * - Normalizes Unicode characters to avoid lookalike chars while retaining info
 * - Allows only: letters, numbers, dots, hyphens, and underscores
 * - Removes all other characters including whitespace, quotes, and control characters
 * - Prevents empty filenames and filenames starting with invalid characters
 * - Limits length to 255 characters
 *
 * @param filename The original filename to sanitize
 * @return A sanitized filename safe for use in HTTP headers and file systems
 */
fun sanitizeFilename(filename: String): String {
    // Remove any path separators to prevent directory traversal
    val nameOnly = filename.substringAfterLast('/').substringAfterLast('\\').ifBlank { "unnamed" }

    // Normalize Unicode to NFD form, then remove combining marks
    // This converts lookalike characters to their ASCII equivalents
    val normalized =
        Normalizer.normalize(nameOnly, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "") // Remove combining marks

    // Allow only safe characters: alphanumeric, dot, hyphen, underscore
    val sanitized = normalized.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(255)

    // Remove leading invalid characters (dots, hyphens, underscores)
    val withoutLeadingInvalid = sanitized.replace(Regex("^[^a-zA-Z0-9]+"), "")

    // If the result is empty or all invalid, generate a UUID-based filename
    return withoutLeadingInvalid.ifBlank {
        UUID.randomUUID().toString()
    }
}

/**
 * Validate that a file path is within an allowed base directory.
 *
 * This prevents path traversal attacks by ensuring the canonical path of the file is within the
 * canonical path of the base directory.
 *
 * @param file The file to validate
 * @param baseDir The base directory that should contain the file
 * @return true if the file is within the base directory, false otherwise
 */
fun isPathWithinBase(file: File, baseDir: File): Boolean {
    val canonicalBaseDir = baseDir.canonicalFile
    val canonicalFile = file.canonicalFile

    // Check exact match or starts with base path + separator
    // This prevents /app matching /app-malicious
    val allowedPath = canonicalBaseDir.path + File.separator
    return canonicalFile.path == canonicalBaseDir.path || canonicalFile.path.startsWith(allowedPath)
}

/**
 * Create a temporary file as a managed resource.
 *
 * The temporary file will be deleted when the resource is closed.
 *
 * @param prefix The prefix string to be used in generating the file's name
 * @param suffix The suffix string to be used in generating the file's name
 */
fun createTempFileResource(prefix: String, suffix: String? = null): Resource<File> = resource {
    install({
        withContext(Dispatchers.LoomIO) {
            File.createTempFile(prefix, suffix).apply { deleteOnExit() }
        }
    }, { file, _ ->
        runInterruptible(Dispatchers.LoomIO) {
            if (file.exists()) file.delete()
        }
    })
}

/**
 * Create a temporary file name without creating the file.
 *
 * The file is created and immediately deleted to reserve the name.
 *
 * @param prefix The prefix string to be used in generating the file's name
 * @param suffix The suffix string to be used in generating the file's name
 */
suspend fun createTempFileName(prefix: String, suffix: String? = null): File =
    withContext(Dispatchers.LoomIO) {
        File.createTempFile(prefix, suffix).apply { delete() }
    }

/**
 * Convert a Java File to a Kotlin Source for reading.
 */
fun File.toKotlinSource(): Resource<Source> = let { file ->
    resource {
        install({
            SystemFileSystem.source(Path(file.absolutePath)).buffered()
        }, { source, _ ->
            source.close()
        })
    }
}

/**
 * Save the contents of a Kotlin Source to a file.
 */
suspend fun Source.saveToFile(file: File): Unit =
    runInterruptible(Dispatchers.LoomIO) {
        BufferedOutputStream(file.outputStream()).use { out ->
            val buffer = ByteArray(8192)
            while (true) {
                val bytesRead = this.readAtMostTo(buffer)
                if (bytesRead == -1) break
                out.write(buffer, 0, bytesRead)
            }
        }
    }

/**
 * Read the source in chunks to process it incrementally.
 */
suspend fun Source.forEachChunk(chunkSize: Int = 8192, block: suspend (ByteArray, Int) -> Unit) {
    val buffer = ByteArray(chunkSize)
    while (true) {
        val bytesRead = this.readAtMostTo(buffer)
        if (bytesRead == -1) break
        block(buffer, bytesRead)
    }
}

/**
 * Calculate the SHA-256 hash of a file's contents.
 */
suspend fun File.calculateHash(): String = resourceScope {
    val digest = MessageDigest.getInstance("SHA-256")
    toKotlinSource().bind().forEachChunk { bytes, len ->
        digest.update(bytes, 0, len)
    }
    val hashBytes = digest.digest()
    return hashBytes.joinToString("") { "%02x".format(it) }
}

/**
 * Perform operations on a file, creating a temporary backup copy,
 * to be restored in case of exceptions.
 */
suspend fun <A> File.deleteWithBackup(block: suspend () -> A): A =
    let { source ->
        resourceScope {
            if (source.exists()) {
                val _ = install({
                    val tempFile = createTempFileName("backup-", source.name)
                    runInterruptible(Dispatchers.LoomIO) {
                        source.copyTo(tempFile, overwrite = true)
                        source.delete()
                    }
                    tempFile
                }, { tempFile, exitCase ->
                    runInterruptible(Dispatchers.LoomIO) {
                        if (exitCase != ExitCase.Completed) {
                            // Restore original file from temp copy in case of error
                            tempFile.copyTo(source, overwrite = true)
                        }
                        tempFile.delete()
                    }
                })
            }
            block()
        }
    }
