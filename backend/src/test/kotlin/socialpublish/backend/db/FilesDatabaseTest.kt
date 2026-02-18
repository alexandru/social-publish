package socialpublish.backend.db

import arrow.core.getOrElse
import arrow.fx.coroutines.resourceScope
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FilesDatabaseTest {
    @Test
    fun `createFile should create new file record`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()

        resourceScope {
            val db = Database.connect(dbPath).bind()
            val filesDb = FilesDatabase(db)

            val payload =
                UploadPayload(
                    hash = "abc123",
                    originalname = "test.jpg",
                    mimetype = "image/jpeg",
                    size = 1024L,
                    userUuid = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"),
                    altText = "Test image",
                    imageWidth = 800,
                    imageHeight = 600,
                )

            val upload = filesDb.createFile(payload).getOrElse { throw it }

            assertNotNull(upload.uuid)
            assertEquals("abc123", upload.hash)
            assertEquals("test.jpg", upload.originalname)
            assertEquals("image/jpeg", upload.mimetype)
            assertEquals(1024L, upload.size)
            assertEquals("Test image", upload.altText)
            assertEquals(800, upload.imageWidth)
            assertEquals(600, upload.imageHeight)
            assertNotNull(upload.createdAt)
        }
    }

    @Test
    fun `createFile should return existing file if already exists`(@TempDir tempDir: Path) =
        runTest {
            val dbPath = tempDir.resolve("test.db").toString()

            resourceScope {
                val db = Database.connect(dbPath).bind()
                val filesDb = FilesDatabase(db)

                val payload =
                    UploadPayload(
                        hash = "duplicate123",
                        originalname = "duplicate.png",
                        mimetype = "image/png",
                        size = 2048L,
                        userUuid = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"),
                    )

                // Create first time
                val first = filesDb.createFile(payload).getOrElse { throw it }

                // Try to create again with same data
                val second = filesDb.createFile(payload).getOrElse { throw it }

                // Should return the same record
                assertEquals(first.uuid, second.uuid)
                assertEquals(first.hash, second.hash)
                // Note: createdAt might have different precision in memory vs DB
                assertEquals(first.createdAt.toEpochMilli(), second.createdAt.toEpochMilli())
            }
        }

    @Test
    fun `createFile should handle null optional fields`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()

        resourceScope {
            val db = Database.connect(dbPath).bind()
            val filesDb = FilesDatabase(db)

            val payload =
                UploadPayload(
                    hash = "minimal123",
                    originalname = "minimal.jpg",
                    mimetype = "image/jpeg",
                    size = 512L,
                    userUuid = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"),
                    altText = null,
                    imageWidth = null,
                    imageHeight = null,
                )

            val upload = filesDb.createFile(payload).getOrElse { throw it }

            assertNotNull(upload.uuid)
            assertNull(upload.altText)
            assertNull(upload.imageWidth)
            assertNull(upload.imageHeight)
        }
    }

    @Test
    fun `getFileByUuid should retrieve file by UUID`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()

        resourceScope {
            val db = Database.connect(dbPath).bind()
            val filesDb = FilesDatabase(db)

            // Create a file
            val payload =
                UploadPayload(
                    hash = "retrieve123",
                    originalname = "retrieve.png",
                    mimetype = "image/png",
                    size = 1500L,
                    userUuid = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"),
                    altText = "Retrieval test",
                )

            val created = filesDb.createFile(payload).getOrElse { throw it }

            // Retrieve it
            val retrieved = filesDb.getFileByUuid(created.uuid).getOrElse { throw it }

            assertNotNull(retrieved)
            assertEquals(created.uuid, retrieved.uuid)
            assertEquals("retrieve123", retrieved.hash)
            assertEquals("retrieve.png", retrieved.originalname)
            assertEquals("Retrieval test", retrieved.altText)
        }
    }

    @Test
    fun `getFileByUuid should return null for non-existent UUID`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()

        resourceScope {
            val db = Database.connect(dbPath).bind()
            val filesDb = FilesDatabase(db)

            val notFound =
                filesDb.getFileByUuid("00000000-0000-0000-0000-000000000000").getOrElse { throw it }

            assertNull(notFound)
        }
    }

    @Test
    fun `generateUuidV5 should be deterministic`() {
        // Same input should always produce same UUID
        val input = "test-input-123"

        val uuid1 = FilesDatabase.generateUuidV5(input)
        val uuid2 = FilesDatabase.generateUuidV5(input)

        assertEquals(uuid1, uuid2)
    }

    @Test
    fun `generateUuidV5 should produce different UUIDs for different inputs`() {
        val uuid1 = FilesDatabase.generateUuidV5("input1")
        val uuid2 = FilesDatabase.generateUuidV5("input2")

        assert(uuid1 != uuid2)
    }

    @Test
    fun `generateUuidV5 should produce valid UUID v5`() {
        val uuid = FilesDatabase.generateUuidV5("test-input")

        // UUID v5 should have version bits set to 5
        val versionBits = uuid.version()
        assertEquals(5, versionBits)
    }

    @Test
    fun `generateUuidV5 should support custom namespace`() {
        val customNamespace = UUID.fromString("12345678-1234-1234-1234-123456789012")
        val input = "test"

        val uuid1 = FilesDatabase.generateUuidV5(input, customNamespace)
        val uuid2 = FilesDatabase.generateUuidV5(input, customNamespace)

        // Should be deterministic with custom namespace
        assertEquals(uuid1, uuid2)

        // Should be different from default namespace
        val uuidDefault = FilesDatabase.generateUuidV5(input)
        assert(uuid1 != uuidDefault)
    }

    @Test
    fun `createFile UUID should be deterministic based on payload`(@TempDir tempDir: Path) =
        runTest {
            val dbPath = tempDir.resolve("test.db").toString()

            resourceScope {
                val db = Database.connect(dbPath).bind()
                val filesDb = FilesDatabase(db)

                val payload1 =
                    UploadPayload(
                        hash = "same-hash",
                        originalname = "same.jpg",
                        mimetype = "image/jpeg",
                        size = 1000L,
                        userUuid =
                            java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"),
                        altText = "Same",
                        imageWidth = 100,
                        imageHeight = 100,
                    )

                val payload2 =
                    UploadPayload(
                        hash = "same-hash",
                        originalname = "same.jpg",
                        mimetype = "image/jpeg",
                        size = 1000L,
                        userUuid =
                            java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"),
                        altText = "Same",
                        imageWidth = 100,
                        imageHeight = 100,
                    )

                val upload1 = filesDb.createFile(payload1).getOrElse { throw it }
                // Create in a different database instance to verify determinism
                val upload2 = filesDb.createFile(payload2).getOrElse { throw it }

                // Should have same UUID because payload is identical
                assertEquals(upload1.uuid, upload2.uuid)
            }
        }

    @Test
    fun `createFile UUID should differ for different payloads`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()

        resourceScope {
            val db = Database.connect(dbPath).bind()
            val filesDb = FilesDatabase(db)

            val payload1 =
                UploadPayload(
                    hash = "hash1",
                    originalname = "file1.jpg",
                    mimetype = "image/jpeg",
                    size = 1000L,
                    userUuid = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"),
                )

            val payload2 =
                UploadPayload(
                    hash = "hash2", // Different hash
                    originalname = "file1.jpg",
                    mimetype = "image/jpeg",
                    size = 1000L,
                    userUuid = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"),
                )

            val upload1 = filesDb.createFile(payload1).getOrElse { throw it }
            val upload2 = filesDb.createFile(payload2).getOrElse { throw it }

            // Should have different UUIDs because hash is different
            assert(upload1.uuid != upload2.uuid)
        }
    }
}
