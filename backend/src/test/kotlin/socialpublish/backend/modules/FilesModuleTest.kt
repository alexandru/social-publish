package socialpublish.backend.modules

import arrow.core.Either
import arrow.core.getOrElse
import java.nio.file.Path
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import socialpublish.backend.models.ValidationError
import socialpublish.backend.testutils.*

class FilesModuleTest {
    @Test
    fun `uploads images and stores originals`(@TempDir tempDir: Path) = runTest {
        val jdbi = createTestDatabase(tempDir)
        val filesModule = createFilesModule(tempDir, jdbi)
        val upload1 =
            filesModule
                .uploadFile(
                    UploadedFile(
                        fileName = "flower1.jpeg",
                        fileBytes = loadTestResourceBytes("flower1.jpeg"),
                        altText = "rose",
                    )
                )
                .getOrElse { error("Unexpected upload error: ${it.errorMessage}") }
        val upload2 =
            filesModule
                .uploadFile(
                    UploadedFile(
                        fileName = "flower2.jpeg",
                        fileBytes = loadTestResourceBytes("flower2.jpeg"),
                        altText = "tulip",
                    )
                )
                .getOrElse { error("Unexpected upload error: ${it.errorMessage}") }

        val processed1 = requireNotNull(filesModule.readImageFile(upload1.uuid))
        val processed2 = requireNotNull(filesModule.readImageFile(upload2.uuid))

        assertEquals("rose", processed1.altText)
        assertEquals("tulip", processed2.altText)

        val stored1 = imageDimensions(processed1.bytes)
        val stored2 = imageDimensions(processed2.bytes)

        // Images should be optimized on upload (max 1600x1600)
        assertTrue(stored1.width <= 1600)
        assertTrue(stored1.height <= 1600)
        assertTrue(stored2.width <= 1600)
        assertTrue(stored2.height <= 1600)

        // Dimensions should match what's stored in the database
        assertEquals(stored1.width, processed1.width)
        assertEquals(stored1.height, processed1.height)
        assertEquals(stored2.width, processed2.width)
        assertEquals(stored2.height, processed2.height)

        // Verify readImageFile returns the same optimized image
        val retrieved = requireNotNull(filesModule.readImageFile(upload1.uuid))
        val retrievedDimensions = imageDimensions(retrieved.bytes)

        assertEquals(stored1.width, retrievedDimensions.width)
        assertEquals(stored1.height, retrievedDimensions.height)
        assertEquals(retrievedDimensions.width, retrieved.width)
        assertEquals(retrievedDimensions.height, retrieved.height)
    }

    @Test
    fun `getFile returns stored file metadata`(@TempDir tempDir: Path) = runTest {
        val jdbi = createTestDatabase(tempDir)
        val filesModule = createFilesModule(tempDir, jdbi)
        val upload =
            filesModule
                .uploadFile(
                    UploadedFile(
                        fileName = "flower1.jpeg",
                        fileBytes = loadTestResourceBytes("flower1.jpeg"),
                        altText = "rose",
                    )
                )
                .getOrElse { error("Unexpected upload error: ${it.errorMessage}") }

        val result = filesModule.getFile(upload.uuid)

        assertTrue(result is Either.Right)
        val storedFile = (result as Either.Right).value
        assertTrue(storedFile.file.exists())
        assertEquals("image/jpeg", storedFile.mimeType)
        assertEquals("flower1.jpeg", storedFile.originalName)
    }

    @Test
    fun `getFile returns not found for unknown uuid`(@TempDir tempDir: Path) = runTest {
        val jdbi = createTestDatabase(tempDir)
        val filesModule = createFilesModule(tempDir, jdbi)

        val result = filesModule.getFile("missing-uuid")

        assertTrue(result is Either.Left)
        val error = (result as Either.Left).value
        assertTrue(error is ValidationError)
        assertEquals(404, error.status)
    }

    @Test
    fun `uploadFile rejects unsupported content`(@TempDir tempDir: Path) = runTest {
        val jdbi = createTestDatabase(tempDir)
        val filesModule = createFilesModule(tempDir, jdbi)

        val result =
            filesModule.uploadFile(
                UploadedFile(
                    fileName = "notes.txt",
                    fileBytes = "not-an-image".toByteArray(),
                    altText = null,
                )
            )

        assertTrue(result is Either.Left)
        val error = (result as Either.Left).value
        assertTrue(error is ValidationError)
        assertEquals(400, error.status)
        assertTrue(error.errorMessage.contains("Only PNG and JPEG images are supported"))
    }
}
