package socialpublish.backend.db

import arrow.core.getOrElse
import arrow.fx.coroutines.resourceScope
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DocumentsDatabaseTest {
    @Test
    fun `createOrUpdate should create new document`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()

        resourceScope {
            val db = Database.connect(dbPath).bind()
            val documentsDb = DocumentsDatabase(db)

            val doc =
                documentsDb
                    .createOrUpdate(
                        kind = "test",
                        userUuid = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"),
                    payload = """{"message": "Hello"}""",
                        searchKey = "test-key-1",
                        tags = listOf(Tag("tag1", "kind1"), Tag("tag2", "kind2")),
                    )
                    .getOrElse { throw it }

            assertNotNull(doc.uuid)
            assertEquals("test", doc.kind)
            assertEquals("""{"message": "Hello"}""", doc.payload)
            assertEquals("test-key-1", doc.searchKey)
            assertEquals(2, doc.tags.size)
            assertEquals("tag1", doc.tags[0].name)
            assertEquals("kind1", doc.tags[0].kind)
        }
    }

    @Test
    fun `createOrUpdate should update existing document by searchKey`(@TempDir tempDir: Path) =
        runTest {
            val dbPath = tempDir.resolve("test.db").toString()

            resourceScope {
                val db = Database.connect(dbPath).bind()
                val documentsDb = DocumentsDatabase(db)

                // Create initial document
                val created =
                    documentsDb
                        .createOrUpdate(
                            kind = "test",
                            userUuid = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"),
                    payload = """{"message": "Original"}""",
                            searchKey = "update-test",
                            tags = listOf(Tag("original", "tag")),
                        )
                        .getOrElse { throw it }

                // Update with same searchKey
                val updated =
                    documentsDb
                        .createOrUpdate(
                            kind = "test",
                            userUuid = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"),
                    payload = """{"message": "Updated"}""",
                            searchKey = "update-test",
                            tags = listOf(Tag("new", "tag")),
                        )
                        .getOrElse { throw it }

                // Should have same UUID but updated content
                assertEquals(created.uuid, updated.uuid)
                assertEquals("""{"message": "Updated"}""", updated.payload)
                assertEquals(1, updated.tags.size)
                assertEquals("new", updated.tags[0].name)
            }
        }

    @Test
    fun `createOrUpdate should generate searchKey if not provided`(@TempDir tempDir: Path) =
        runTest {
            val dbPath = tempDir.resolve("test.db").toString()

            resourceScope {
                val db = Database.connect(dbPath).bind()
                val documentsDb = DocumentsDatabase(db)

                val doc =
                    documentsDb
                        .createOrUpdate(kind = "test", userUuid = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"),
                    payload = """{"message": "Auto key"}""")
                        .getOrElse { throw it }

                assertNotNull(doc.searchKey)
                // Generated key should be in format "kind:uuid"
                assert(doc.searchKey.startsWith("test:"))
            }
        }

    @Test
    fun `searchByKey should find document by searchKey`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()

        resourceScope {
            val db = Database.connect(dbPath).bind()
            val documentsDb = DocumentsDatabase(db)

            // Create document
            @Suppress("UNUSED_VARIABLE")
            val created =
                documentsDb
                    .createOrUpdate(
                        kind = "test",
                        userUuid = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"),
                    payload = """{"data": "searchable"}""",
                        searchKey = "find-me",
                        tags = listOf(Tag("searchable", "test")),
                    )
                    .getOrElse { throw it }

            // Search for it
            val found = documentsDb.searchByKey("find-me").getOrElse { throw it }

            assertNotNull(found)
            assertEquals("""{"data": "searchable"}""", found.payload)
            assertEquals("find-me", found.searchKey)
            assertEquals(1, found.tags.size)
            assertEquals("searchable", found.tags[0].name)
        }
    }

    @Test
    fun `searchByKey should return null for non-existent key`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()

        resourceScope {
            val db = Database.connect(dbPath).bind()
            val documentsDb = DocumentsDatabase(db)

            val notFound = documentsDb.searchByKey("does-not-exist").getOrElse { throw it }

            assertNull(notFound)
        }
    }

    @Test
    fun `searchByUuid should find document by UUID`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()

        resourceScope {
            val db = Database.connect(dbPath).bind()
            val documentsDb = DocumentsDatabase(db)

            // Create document
            val created =
                documentsDb
                    .createOrUpdate(
                        kind = "test",
                        userUuid = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"),
                    payload = """{"uuid": "test"}""",
                        tags = listOf(Tag("uuid-tag", "test")),
                    )
                    .getOrElse { throw it }

            // Search by UUID
            val found = documentsDb.searchByUuid(created.uuid).getOrElse { throw it }

            assertNotNull(found)
            assertEquals(created.uuid, found.uuid)
            assertEquals("""{"uuid": "test"}""", found.payload)
            assertEquals(1, found.tags.size)
        }
    }

    @Test
    fun `searchByUuid should return null for non-existent UUID`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()

        resourceScope {
            val db = Database.connect(dbPath).bind()
            val documentsDb = DocumentsDatabase(db)

            val notFound =
                documentsDb.searchByUuid("00000000-0000-0000-0000-000000000000").getOrElse {
                    throw it
                }

            assertNull(notFound)
        }
    }

    @Test
    fun `getAll should retrieve all documents of a kind`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()

        resourceScope {
            val db = Database.connect(dbPath).bind()
            val documentsDb = DocumentsDatabase(db)

            // Create multiple documents
            @Suppress("UNUSED_VARIABLE")
            val post1 =
                documentsDb
                    .createOrUpdate(kind = "blog", userUuid = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"),
                    payload = """{"title": "Post 1"}""")
                    .getOrElse { throw it }
            @Suppress("UNUSED_VARIABLE")
            val post2 =
                documentsDb
                    .createOrUpdate(kind = "blog", userUuid = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"),
                    payload = """{"title": "Post 2"}""")
                    .getOrElse { throw it }
            @Suppress("UNUSED_VARIABLE")
            val note1 =
                documentsDb
                    .createOrUpdate(kind = "note", userUuid = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"),
                    payload = """{"title": "Note 1"}""")
                    .getOrElse { throw it }

            // Get all blogs
            val blogs = documentsDb.getAll("blog").getOrElse { throw it }

            assertEquals(2, blogs.size)
            assert(blogs.all { it.kind == "blog" })
        }
    }

    @Test
    fun `getAll should return documents in CREATED_AT_DESC order`(@TempDir tempDir: Path) =
        runTest {
            val dbPath = tempDir.resolve("test.db").toString()

            resourceScope {
                val db = Database.connect(dbPath).bind()
                val documentsDb = DocumentsDatabase(db)

                // Create documents in sequence
                val first =
                    documentsDb
                        .createOrUpdate(kind = "test", userUuid = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"),
                    payload = """{"order": 1}""")
                        .getOrElse { throw it }
                // Small delay to ensure different timestamps (DB stores millis precision)
                @Suppress("UnusedReturnValue") kotlinx.coroutines.delay(10)
                val second =
                    documentsDb
                        .createOrUpdate(kind = "test", userUuid = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"),
                    payload = """{"order": 2}""")
                        .getOrElse { throw it }
                @Suppress("UnusedReturnValue") kotlinx.coroutines.delay(10)
                val third =
                    documentsDb
                        .createOrUpdate(kind = "test", userUuid = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"),
                    payload = """{"order": 3}""")
                        .getOrElse { throw it }

                val all =
                    documentsDb
                        .getAll("test", DocumentsDatabase.OrderBy.CREATED_AT_DESC)
                        .getOrElse { throw it }

                assertEquals(3, all.size)
                // Should be in reverse chronological order (newest first)
                assertEquals(third.uuid, all[0].uuid)
                assertEquals(second.uuid, all[1].uuid)
                assertEquals(first.uuid, all[2].uuid)

                // Verify timestamps are actually in descending order
                assert(all[0].createdAt >= all[1].createdAt)
                assert(all[1].createdAt >= all[2].createdAt)
            }
        }

    @Test
    fun `getAll should return empty list for non-existent kind`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()

        resourceScope {
            val db = Database.connect(dbPath).bind()
            val documentsDb = DocumentsDatabase(db)

            val result = documentsDb.getAll("non-existent").getOrElse { throw it }

            assertEquals(0, result.size)
        }
    }

    @Test
    fun `tags should be persisted and retrieved correctly`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()

        resourceScope {
            val db = Database.connect(dbPath).bind()
            val documentsDb = DocumentsDatabase(db)

            val tags = listOf(Tag("tag1", "type1"), Tag("tag2", "type2"), Tag("tag3", "type3"))

            val created =
                documentsDb
                    .createOrUpdate(kind = "tagged", userUuid = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"),
                    payload = """{"tagged": true}""", tags = tags)
                    .getOrElse { throw it }

            assertEquals(3, created.tags.size)

            // Retrieve and verify tags are still there
            val retrieved = documentsDb.searchByUuid(created.uuid).getOrElse { throw it }

            assertNotNull(retrieved)
            assertEquals(3, retrieved.tags.size)
            assertEquals("tag1", retrieved.tags[0].name)
            assertEquals("type1", retrieved.tags[0].kind)
        }
    }

    @Test
    fun `updating document should replace tags`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()

        resourceScope {
            val db = Database.connect(dbPath).bind()
            val documentsDb = DocumentsDatabase(db)

            // Create with initial tags
            @Suppress("UNUSED_VARIABLE")
            val initial =
                documentsDb
                    .createOrUpdate(
                        kind = "test",
                        userUuid = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"),
                    payload = """{"v": 1}""",
                        searchKey = "tag-update",
                        tags = listOf(Tag("old1", "kind"), Tag("old2", "kind")),
                    )
                    .getOrElse { throw it }

            // Update with different tags
            val updated =
                documentsDb
                    .createOrUpdate(
                        kind = "test",
                        userUuid = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"),
                    payload = """{"v": 2}""",
                        searchKey = "tag-update",
                        tags = listOf(Tag("new1", "kind")),
                    )
                    .getOrElse { throw it }

            // Should only have the new tag
            assertEquals(1, updated.tags.size)
            assertEquals("new1", updated.tags[0].name)
        }
    }
}
