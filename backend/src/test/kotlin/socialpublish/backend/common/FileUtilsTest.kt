package socialpublish.backend.common

import arrow.fx.coroutines.resourceScope
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.asSource
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FileUtilsTest {

    @Test
    fun `sanitizeFilename should handle normal filenames`() {
        assertEquals("document.pdf", sanitizeFilename("document.pdf"))
        assertEquals("my_file.txt", sanitizeFilename("my_file.txt"))
        assertEquals("image-2024.png", sanitizeFilename("image-2024.png"))
    }

    @Test
    fun `sanitizeFilename should remove path separators`() {
        assertEquals("test.txt", sanitizeFilename("/path/to/test.txt"))
        assertEquals("test.txt", sanitizeFilename("..\\..\\test.txt"))
        assertEquals("file.doc", sanitizeFilename("../../sensitive/file.doc"))
    }

    @Test
    fun `sanitizeFilename should replace special characters with underscores`() {
        assertEquals("my_file_name.txt", sanitizeFilename("my file name.txt"))
        assertEquals("file_2024_.pdf", sanitizeFilename("file@2024!.pdf"))
        assertEquals("document_.doc", sanitizeFilename("document*.doc"))
    }

    @Test
    fun `sanitizeFilename should normalize Unicode characters`() {
        // Test with accented characters - they should be normalized
        val result = sanitizeFilename("café.txt")
        // After normalization and removing combining marks, é becomes e
        assertEquals("cafe.txt", result)
    }

    @Test
    fun `sanitizeFilename should remove leading invalid characters`() {
        assertEquals("file.txt", sanitizeFilename("...file.txt"))
        assertEquals("file.txt", sanitizeFilename("---file.txt"))
        assertEquals("hidden.txt", sanitizeFilename(".hidden.txt"))
    }

    @Test
    fun `sanitizeFilename should generate UUID for completely invalid names`() {
        val result1 = sanitizeFilename("...")
        val result2 = sanitizeFilename("///")
        val result3 = sanitizeFilename("@@@")

        // Should be valid UUIDs (format: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx)
        // UUIDs contain hyphens which are allowed characters
        assertTrue(result1.isNotBlank() && result1.all { it.isLetterOrDigit() || it == '-' })
        assertTrue(result2.isNotBlank() && result2.all { it.isLetterOrDigit() || it == '-' })
        assertTrue(result3.isNotBlank() && result3.all { it.isLetterOrDigit() || it == '-' })
    }

    @Test
    fun `sanitizeFilename should generate UUID for empty input`() {
        val result = sanitizeFilename("")
        // Should be a valid UUID
        // UUIDs contain hyphens which are allowed characters
        assertTrue(result.isNotBlank() && result.all { it.isLetterOrDigit() || it == '-' })
    }

    @Test
    fun `sanitizeFilename should limit length to 255 characters`() {
        val longName = "a".repeat(300) + ".txt"
        val result = sanitizeFilename(longName)
        assertTrue(result.length <= 255)
    }

    @Test
    fun `sanitizeFilename should prevent header injection`() {
        // \r\n are two characters, so they become __ (two underscores)
        assertEquals("file__name.txt", sanitizeFilename("file\r\nname.txt"))
        assertEquals("test_.pdf", sanitizeFilename("test\".pdf"))
        assertEquals("doc_.txt", sanitizeFilename("doc'.txt"))
    }

    @Test
    fun `isPathWithinBase should allow files in base directory`(@TempDir tempDir: File) {
        val baseDir = tempDir
        val file = File(baseDir, "test.txt")
        file.createNewFile()

        assertTrue(isPathWithinBase(file, baseDir))
    }

    @Test
    fun `isPathWithinBase should allow files in subdirectories`(@TempDir tempDir: File) {
        val baseDir = tempDir
        val subDir = File(baseDir, "subdir")
        subDir.mkdir()
        val file = File(subDir, "test.txt")
        file.createNewFile()

        assertTrue(isPathWithinBase(file, baseDir))
    }

    @Test
    fun `isPathWithinBase should reject files outside base directory`(@TempDir tempDir: File) {
        val baseDir = File(tempDir, "base")
        baseDir.mkdir()
        val outsideFile = File(tempDir, "outside.txt")
        outsideFile.createNewFile()

        assertFalse(isPathWithinBase(outsideFile, baseDir))
    }

    @Test
    fun `isPathWithinBase should reject path traversal attempts`(@TempDir tempDir: File) {
        val baseDir = File(tempDir, "base")
        baseDir.mkdir()

        // Try to escape using ../
        val traversalFile = File(baseDir, "../outside.txt")

        // Even if the file exists, it should be rejected
        File(tempDir, "outside.txt").createNewFile()

        assertFalse(isPathWithinBase(traversalFile, baseDir))
    }

    @Test
    fun `isPathWithinBase should prevent base path prefix confusion`(@TempDir tempDir: File) {
        // Create two directories: /app and /app-malicious
        val appDir = File(tempDir, "app")
        val maliciousDir = File(tempDir, "app-malicious")
        appDir.mkdir()
        maliciousDir.mkdir()

        val maliciousFile = File(maliciousDir, "evil.txt")
        maliciousFile.createNewFile()

        // Should NOT allow file from app-malicious when base is app
        assertFalse(isPathWithinBase(maliciousFile, appDir))
    }

    @Test
    fun `isPathWithinBase should handle base directory itself`(@TempDir tempDir: File) {
        val baseDir = tempDir

        // The base directory itself should be considered within base
        assertTrue(isPathWithinBase(baseDir, baseDir))
    }

    @Test
    fun `forEachChunk reads source incrementally`() = runTest {
        val bytes = ByteArray(20) { it.toByte() }
        val source = ByteReadChannel(bytes).asSource().buffered()
        val chunks = mutableListOf<ByteArray>()

        source.forEachChunk(chunkSize = 6) { buffer, len -> chunks.add(buffer.copyOf(len)) }

        val reconstructed = chunks.fold(ByteArray(0)) { acc, chunk -> acc + chunk }
        assertEquals(bytes.toList(), reconstructed.toList())
        assertEquals(listOf(6, 6, 6, 2), chunks.map { it.size })
    }

    @Test
    fun `calculateHash matches SHA-256 output`(@TempDir tempDir: File) = runTest {
        val file = File(tempDir, "hash.txt").apply { writeText("hello") }

        val hash = file.calculateHash()

        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", hash)
    }

    @Test
    fun `UploadSource from source creates and cleans temp file`(): Unit = runTest {
        val tempPath = resourceScope {
            val source = ByteReadChannel("data".toByteArray()).asSource().buffered()
            val file = UploadSource.FromSource(source).asFileResource().bind()
            assertTrue(file.exists())
            file.absolutePath
        }

        assertFalse(File(tempPath).exists())
    }

    @Test
    fun `createTempFileResource cleans up after scope`(): Unit = runTest {
        val tempPath = resourceScope {
            val file = createTempFileResource("tmp-").bind()
            assertTrue(file.exists())
            file.absolutePath
        }

        assertFalse(File(tempPath).exists())
    }

    @Test
    fun `createTempFileResource accepts sanitized suffix`(): Unit = runTest {
        val unsafeName = "../../weird/name/with spaces?.png"
        val safeSuffix = sanitizeFilename(unsafeName)

        val tempPath = resourceScope {
            val file = createTempFileResource("tmp-", safeSuffix).bind()
            assertTrue(file.exists())
            assertTrue(file.name.endsWith(safeSuffix))
            file.absolutePath
        }

        assertFalse(File(tempPath).exists())
    }

    @Test
    fun `createTempFileName returns a non-existing file`(@TempDir tempDir: File) = runTest {
        val file = createTempFileName("reserved-", ".tmp")

        try {
            assertFalse(file.exists())
            file.writeText("data")
            assertTrue(file.exists())
        } finally {
            file.delete()
        }
    }

    @Test
    fun `saveToFile writes source bytes`(@TempDir tempDir: File) = runTest {
        val bytes = ByteArray(12) { (it * 2).toByte() }
        val source = ByteReadChannel(bytes).asSource().buffered()
        val file = File(tempDir, "out.bin")

        source.saveToFile(file)

        assertEquals(bytes.toList(), file.readBytes().toList())
    }

    @Test
    fun `deleteWithBackup restores original on failure`(@TempDir tempDir: File) = runTest {
        val file = File(tempDir, "data.txt").apply { writeText("original") }

        assertFails {
            file.deleteWithBackup {
                file.writeText("new")
                error("boom")
            }
        }

        assertTrue(file.exists())
        assertEquals("original", file.readText())
    }
}
