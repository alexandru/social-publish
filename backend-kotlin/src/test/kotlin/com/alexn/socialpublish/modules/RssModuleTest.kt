package com.alexn.socialpublish.modules

import arrow.core.Either
import com.alexn.socialpublish.db.FilesDatabase
import com.alexn.socialpublish.db.PostsDatabase
import com.alexn.socialpublish.models.NewPostRequest
import com.alexn.socialpublish.models.NewRssPostResponse
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.KotlinPlugin
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RssModuleTest {
    private lateinit var baseUrl: String
    private lateinit var jdbi: Jdbi
    private lateinit var postsDb: PostsDatabase
    private lateinit var filesDb: FilesDatabase
    private lateinit var rssModule: RssModule

    @BeforeEach
    fun setup(
        @TempDir tempDir: Path,
    ) {
        baseUrl = "http://localhost:3000"
        val dbPath = tempDir.resolve("test.db").toString()

        jdbi =
            Jdbi.create("jdbc:sqlite:$dbPath")
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
                """.trimIndent(),
            )
            handle.execute(
                """
                CREATE TABLE document_tags (
                   document_uuid VARCHAR(36) NOT NULL,
                   name VARCHAR(255) NOT NULL,
                   kind VARCHAR(255) NOT NULL,
                   PRIMARY KEY (document_uuid, name, kind)
                )
                """.trimIndent(),
            )
        }

        val documentsDb = com.alexn.socialpublish.db.DocumentsDatabase(jdbi)
        postsDb = PostsDatabase(documentsDb)
        filesDb = FilesDatabase(jdbi)
        rssModule = RssModule(baseUrl, postsDb, filesDb)
    }

    @Test
    fun `should create RSS post successfully`() {
        val request =
            NewPostRequest(
                content = "Test RSS post",
                link = "https://example.com",
                language = "en",
            )

        val result =
            kotlinx.coroutines.runBlocking {
                rssModule.createPost(request)
            }

        assertTrue(result.isRight())
        when (result) {
            is Either.Right -> {
                val response = result.value as NewRssPostResponse
                assertNotNull(response.uri)
                assertTrue(response.uri.contains("/rss/"))
            }
            is Either.Left -> {
                // Should not happen
                assertTrue(false, "Expected success but got error")
            }
        }
    }

    @Test
    fun `should reject empty content`() {
        val request =
            NewPostRequest(
                content = "",
                link = null,
                language = null,
            )

        val result =
            kotlinx.coroutines.runBlocking {
                rssModule.createPost(request)
            }

        assertTrue(result.isLeft())
    }

    @Test
    fun `should extract hashtags from content`() {
        val request =
            NewPostRequest(
                content = "Test post with #hashtag and #another",
                link = null,
                language = null,
            )

        val result =
            kotlinx.coroutines.runBlocking {
                rssModule.createPost(request)
            }

        assertTrue(result.isRight())

        // Verify hashtags were extracted
        val posts = postsDb.getAll()
        assertEquals(1, posts.size)
        assertNotNull(posts[0].tags)
        assertTrue(posts[0].tags!!.contains("hashtag"))
        assertTrue(posts[0].tags!!.contains("another"))
    }

    @Test
    fun `should generate RSS feed`() {
        // Create a test post
        val request =
            NewPostRequest(
                content = "Test content for RSS",
                link = "https://example.com",
            )

        kotlinx.coroutines.runBlocking {
            rssModule.createPost(request)
        }

        // Generate RSS feed
        val rss = rssModule.generateRss()

        assertNotNull(rss)
        assertTrue(rss.contains("<?xml"))
        assertTrue(rss.contains("<rss"))
        assertTrue(rss.contains("Test content for RSS"))
    }

    @Test
    fun `should filter RSS feed by links`() {
        // Create posts with and without links
        kotlinx.coroutines.runBlocking {
            rssModule.createPost(NewPostRequest(content = "With link", link = "https://example.com"))
            rssModule.createPost(NewPostRequest(content = "Without link", link = null))
        }

        val rssWithLinks = rssModule.generateRss(filterByLinks = "include")
        assertTrue(rssWithLinks.contains("With link"))
        assertTrue(!rssWithLinks.contains("Without link"))

        val rssWithoutLinks = rssModule.generateRss(filterByLinks = "exclude")
        assertTrue(!rssWithoutLinks.contains("With link"))
        assertTrue(rssWithoutLinks.contains("Without link"))
    }
}
