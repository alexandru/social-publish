package socialpublish.backend.db

import arrow.core.getOrElse
import arrow.fx.coroutines.resourceScope
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import socialpublish.backend.testutils.TEST_USER_UUID

class PostsDatabaseTest {
    @Test
    fun `test create and retrieve post`(@TempDir tempDir: Path) = runTest {
        // Setup
        val dbPath = tempDir.resolve("test.db").toString()

        resourceScope {
            val db = Database.connect(dbPath).bind()
            val documentsDb = DocumentsDatabase(db)
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

            val created =
                postsDb.create(TEST_USER_UUID, payload, listOf("mastodon", "bluesky")).getOrElse {
                    throw it
                }
            val retrieved =
                postsDb.searchByUuid(TEST_USER_UUID, created.uuid).getOrElse { throw it }

            // Verify
            assertNotNull(created.uuid)
            assertEquals("Hello, World!", created.content)
            assertEquals(2, created.targets.size)

            // Retrieve
            assertNotNull(retrieved)
            assertEquals(created.content, retrieved.content)
        }
    }
}
