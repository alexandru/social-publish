package socialpublish.backend.db

import arrow.core.getOrElse
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.KotlinPlugin
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PostsDatabaseTest {
    @Test
    fun `test create and retrieve post`(@TempDir tempDir: Path) = runTest {
        // Setup
        val dbPath = tempDir.resolve("test.db").toString()
        val jdbi = Jdbi.create("jdbc:sqlite:$dbPath").installPlugin(KotlinPlugin())

        // Run migrations
        jdbi.useHandle<Exception> { handle ->
            handle.execute(
                """
                CREATE TABLE documents (
                    uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                    search_key VARCHAR(255) UNIQUE NOT NULL,
                    kind VARCHAR(255) NOT NULL,
                    payload TEXT NOT NULL,
                    created_at INTEGER NOT NULL
                )
                """
                    .trimIndent()
            )
            handle.execute(
                """
                CREATE TABLE document_tags (
                   document_uuid VARCHAR(36) NOT NULL,
                   name VARCHAR(255) NOT NULL,
                   kind VARCHAR(255) NOT NULL,
                   PRIMARY KEY (document_uuid, name, kind)
                )
                """
                    .trimIndent()
            )
        }

        val documentsDb = DocumentsDatabase(jdbi)
        val postsDb = PostsDatabase(documentsDb)

        // Test
        val payload =
            PostPayload(
                content = "Hello, World!",
                link = "https://example.com",
                tags = listOf("test"),
                language = "en",
                images = emptyList(),
            )

        val created = postsDb.create(payload, listOf("mastodon", "bluesky")).getOrElse { throw it }
        val retrieved = postsDb.searchByUuid(created.uuid).getOrElse { throw it }

        // Verify
        assertNotNull(created.uuid)
        assertEquals("Hello, World!", created.content)
        assertEquals(2, created.targets.size)

        // Retrieve
        assertNotNull(retrieved)
        assertEquals(created.content, retrieved.content)
    }
}
