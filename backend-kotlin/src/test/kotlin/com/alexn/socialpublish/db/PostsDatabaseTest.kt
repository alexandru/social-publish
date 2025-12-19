package com.alexn.socialpublish.db

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.KotlinPlugin

class PostsDatabaseTest {
    
    @Test
    fun `test create and retrieve post`(@TempDir tempDir: Path) {
        // Setup
        val dbPath = tempDir.resolve("test.db").toString()
        val jdbi = Jdbi.create("jdbc:sqlite:$dbPath")
            .installPlugin(KotlinPlugin())
        
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
                """.trimIndent()
            )
            handle.execute(
                """
                CREATE TABLE document_tags (
                   document_uuid VARCHAR(36) NOT NULL,
                   name VARCHAR(255) NOT NULL,
                   kind VARCHAR(255) NOT NULL,
                   PRIMARY KEY (document_uuid, name, kind)
                )
                """.trimIndent()
            )
        }
        
        val documentsDb = DocumentsDatabase(jdbi)
        val postsDb = PostsDatabase(documentsDb)
        
        // Test
        val payload = PostPayload(
            content = "Hello, World!",
            link = "https://example.com",
            tags = listOf("test"),
            language = "en",
            images = emptyList()
        )
        
        val post = postsDb.create(payload, listOf("mastodon", "bluesky"))
        
        // Verify
        assertNotNull(post.uuid)
        assertEquals("Hello, World!", post.content)
        assertEquals(2, post.targets.size)
        
        // Retrieve
        val retrieved = postsDb.searchByUuid(post.uuid)
        assertNotNull(retrieved)
        assertEquals(post.content, retrieved.content)
    }
}
