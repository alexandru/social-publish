package socialpublish.backend.modules

import arrow.core.Either
import arrow.core.getOrElse
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.asSource
import java.nio.file.Path
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import socialpublish.backend.common.UploadSource
import socialpublish.backend.common.ValidationError
import socialpublish.backend.db.UUIDv7
import socialpublish.backend.testutils.createFilesModule
import socialpublish.backend.testutils.createTestDatabase
import socialpublish.backend.testutils.createTestSession
import socialpublish.backend.testutils.imageDimensions
import socialpublish.backend.testutils.loadTestResourceBytes

class FilesModuleTest {
    private val testSession by lazy {
        createTestSession(
            UUIDv7.fromString("00000000-0000-0000-0000-000000000001")
        )
    }
    private val testSessionB by lazy {
        createTestSession(
            UUIDv7.fromString("00000000-0000-0000-0000-000000000002")
        )
    }

    @Test
    fun `uploads images and stores originals`(@TempDir tempDir: Path) =
        runTest {
            val jdbi = createTestDatabase(tempDir)
            val filesModule = createFilesModule(tempDir, jdbi)
            val upload1 =
                context(testSession) {
                        filesModule.uploadFile(
                            UploadedFile(
                                fileName = "flower1.jpeg",
                                source =
                                    UploadSource.FromSource(
                                        ByteReadChannel(
                                                loadTestResourceBytes(
                                                    "flower1.jpeg"
                                                )
                                            )
                                            .asSource()
                                            .buffered()
                                    ),
                                altText = "rose",
                            )
                        )
                    }
                    .getOrElse {
                        error("Unexpected upload error: ${it.errorMessage}")
                    }
            val upload2 =
                context(testSession) {
                        filesModule.uploadFile(
                            UploadedFile(
                                fileName = "flower2.jpeg",
                                source =
                                    UploadSource.FromSource(
                                        ByteReadChannel(
                                                loadTestResourceBytes(
                                                    "flower2.jpeg"
                                                )
                                            )
                                            .asSource()
                                            .buffered()
                                    ),
                                altText = "tulip",
                            )
                        )
                    }
                    .getOrElse {
                        error("Unexpected upload error: ${it.errorMessage}")
                    }

            val processed1 =
                context(testSession) {
                    requireNotNull(filesModule.readImageFile(upload1.uuid))
                }
            val processed2 =
                context(testSession) {
                    requireNotNull(filesModule.readImageFile(upload2.uuid))
                }

            assertEquals("rose", processed1.altText)
            assertEquals("tulip", processed2.altText)

            val stored1 = imageDimensions(processed1.source.readBytes())
            val stored2 = imageDimensions(processed2.source.readBytes())

            assertTrue(stored1.width <= 1600)
            assertTrue(stored1.height <= 1600)
            assertTrue(stored2.width <= 1600)
            assertTrue(stored2.height <= 1600)

            assertEquals(stored1.width, processed1.width)
            assertEquals(stored1.height, processed1.height)
            assertEquals(stored2.width, processed2.width)
            assertEquals(stored2.height, processed2.height)

            val retrieved =
                context(testSession) {
                    requireNotNull(filesModule.readImageFile(upload1.uuid))
                }
            val retrievedDimensions =
                imageDimensions(retrieved.source.readBytes())

            assertEquals(stored1.width, retrievedDimensions.width)
            assertEquals(stored1.height, retrievedDimensions.height)
            assertEquals(retrievedDimensions.width, retrieved.width)
            assertEquals(retrievedDimensions.height, retrieved.height)
        }

    @Test
    fun `getFile returns stored file metadata`(@TempDir tempDir: Path) =
        runTest {
            val jdbi = createTestDatabase(tempDir)
            val filesModule = createFilesModule(tempDir, jdbi)
            val upload =
                context(testSession) {
                        filesModule.uploadFile(
                            UploadedFile(
                                fileName = "flower1.jpeg",
                                source =
                                    UploadSource.FromSource(
                                        ByteReadChannel(
                                                loadTestResourceBytes(
                                                    "flower1.jpeg"
                                                )
                                            )
                                            .asSource()
                                            .buffered()
                                    ),
                                altText = "rose",
                            )
                        )
                    }
                    .getOrElse {
                        error("Unexpected upload error: ${it.errorMessage}")
                    }

            val result = filesModule.getFile(upload.uuid)

            assertTrue(result is Either.Right)
            val storedFile = (result as Either.Right).value
            assertTrue(storedFile.file.exists())
            assertEquals("image/jpeg", storedFile.mimeType)
            assertEquals("flower1.jpeg", storedFile.originalName)
        }

    @Test
    fun `updateAltText updates metadata without re-uploading file`(
        @TempDir tempDir: Path
    ) = runTest {
        val jdbi = createTestDatabase(tempDir)
        val filesModule = createFilesModule(tempDir, jdbi)
        val upload =
            context(testSession) {
                    filesModule.uploadFile(
                        UploadedFile(
                            fileName = "flower1.jpeg",
                            source =
                                UploadSource.FromSource(
                                    ByteReadChannel(
                                            loadTestResourceBytes(
                                                "flower1.jpeg"
                                            )
                                        )
                                        .asSource()
                                        .buffered()
                                ),
                            altText = null,
                        )
                    )
                }
                .getOrElse {
                    error("Unexpected upload error: ${it.errorMessage}")
                }

        val updated =
            context(testSession) {
                    filesModule.updateAltText(upload.uuid, "rose garden")
                }
                .getOrElse {
                    error("Unexpected update error: ${it.errorMessage}")
                }
        val processed =
            context(testSession) {
                requireNotNull(filesModule.readImageFile(updated.uuid))
            }

        assertEquals(upload.uuid, updated.uuid)
        assertEquals("rose garden", processed.altText)
    }

    @Test
    fun `updateAltText cannot update another users upload`(
        @TempDir tempDir: Path
    ) = runTest {
        val jdbi = createTestDatabase(tempDir)
        val filesModule = createFilesModule(tempDir, jdbi)
        val upload =
            context(testSession) {
                    filesModule.uploadFile(
                        UploadedFile(
                            fileName = "flower1.jpeg",
                            source =
                                UploadSource.FromSource(
                                    ByteReadChannel(
                                            loadTestResourceBytes(
                                                "flower1.jpeg"
                                            )
                                        )
                                        .asSource()
                                        .buffered()
                                ),
                            altText = "rose",
                        )
                    )
                }
                .getOrElse {
                    error("Unexpected upload error: ${it.errorMessage}")
                }

        val result =
            context(testSessionB) {
                filesModule.updateAltText(upload.uuid, "stolen")
            }

        assertTrue(result is Either.Left)
        val error = (result as Either.Left).value
        assertTrue(error is ValidationError)
        assertEquals(404, error.status)
    }

    @Test
    fun `getFile returns not found for unknown uuid`(@TempDir tempDir: Path) =
        runTest {
            val jdbi = createTestDatabase(tempDir)
            val filesModule = createFilesModule(tempDir, jdbi)

            val result = filesModule.getFile("missing-uuid")

            assertTrue(result is Either.Left)
            val error = (result as Either.Left).value
            assertTrue(error is ValidationError)
            assertEquals(404, error.status)
        }

    @Test
    fun `uploadFile rejects unsupported content`(@TempDir tempDir: Path) =
        runTest {
            val jdbi = createTestDatabase(tempDir)
            val filesModule = createFilesModule(tempDir, jdbi)

            val result =
                context(testSession) {
                    filesModule.uploadFile(
                        UploadedFile(
                            fileName = "notes.txt",
                            source =
                                UploadSource.FromSource(
                                    ByteReadChannel(
                                            "not-an-image".toByteArray()
                                        )
                                        .asSource()
                                        .buffered()
                                ),
                            altText = null,
                        )
                    )
                }

            assertTrue(result is Either.Left)
            val error = (result as Either.Left).value
            assertTrue(error is ValidationError)
            assertEquals(400, error.status)
            assertTrue(
                error.errorMessage.contains(
                    "Only PNG and JPEG images are supported"
                )
            )
        }

    @Test
    fun `readImageFile returns null for another users upload`(
        @TempDir tempDir: Path
    ) = runTest {
        val jdbi = createTestDatabase(tempDir)
        val filesModule = createFilesModule(tempDir, jdbi)
        val upload =
            context(testSession) {
                    filesModule.uploadFile(
                        UploadedFile(
                            fileName = "flower1.jpeg",
                            source =
                                UploadSource.FromSource(
                                    ByteReadChannel(
                                            loadTestResourceBytes(
                                                "flower1.jpeg"
                                            )
                                        )
                                        .asSource()
                                        .buffered()
                                ),
                            altText = "rose",
                        )
                    )
                }
                .getOrElse {
                    error("Unexpected upload error: ${it.errorMessage}")
                }

        val ownerRead =
            context(testSession) {
                requireNotNull(filesModule.readImageFile(upload.uuid))
            }
        val otherRead =
            context(testSessionB) { filesModule.readImageFile(upload.uuid) }

        assertEquals("rose", ownerRead.altText)
        assertEquals(null, otherRead)
    }
}
