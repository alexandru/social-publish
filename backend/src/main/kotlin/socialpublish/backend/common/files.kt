package socialpublish.backend.common

import java.io.File
import java.text.Normalizer
import java.util.UUID

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
    return if (withoutLeadingInvalid.isBlank()) {
        UUID.randomUUID().toString()
    } else {
        withoutLeadingInvalid
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
