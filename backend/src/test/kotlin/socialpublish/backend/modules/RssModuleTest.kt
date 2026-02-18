package socialpublish.backend.modules

import arrow.core.Either
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import socialpublish.backend.common.NewPostRequest
import socialpublish.backend.common.NewRssPostResponse
import socialpublish.backend.db.DocumentsDatabase
import socialpublish.backend.db.FilesDatabase
import socialpublish.backend.db.PostsDatabase
import socialpublish.backend.testutils.createTestDatabase

class RssModuleTest {
    private lateinit var rssModule: RssModule
    private lateinit var postsDb: PostsDatabase
    private lateinit var filesDb: FilesDatabase
    private val testUserUuid = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001")

    @BeforeEach
    fun setup(@TempDir tempDir: Path) = runTest {
        val db = createTestDatabase(tempDir)
        val documentsDb = DocumentsDatabase(db)
        postsDb = PostsDatabase(documentsDb)
        filesDb = FilesDatabase(db)
        rssModule = RssModule("http://localhost:3000", postsDb, filesDb)
    }

    @Test
    fun `createPost creates valid RSS post with tags`() = runTest {
        val request =
            NewPostRequest(
                content = "Test post #kotlin #testing",
                targets = listOf("rss"),
                link = "https://example.com",
            )

        val result =
            rssModule.createPost(request, testUserUuid)

        assertTrue(result is Either.Right)
        val response = (result as Either.Right).value
        assertTrue(response is NewRssPostResponse)
        val rssResponse = response as NewRssPostResponse
        assertTrue(rssResponse.uri.startsWith("http://localhost:3000/rss/"))
    }

    @Test
    fun `createPost extracts hashtags correctly`() = runTest {
        val request =
            NewPostRequest(
                content = "Post with #kotlin #programming #test",
                targets = listOf("rss"),
            )

        val result =
            rssModule.createPost(request, testUserUuid)

        assertTrue(result is Either.Right)

        // Verify the post was created with tags by retrieving all posts
        val posts = postsDb.getAllForUser(testUserUuid)
        assertTrue(posts is Either.Right)
        val postsList = (posts as Either.Right).value
        assertEquals(1, postsList.size)
        assertEquals(3, postsList[0].tags?.size)
        assertTrue(postsList[0].tags?.contains("kotlin") == true)
        assertTrue(postsList[0].tags?.contains("programming") == true)
        assertTrue(postsList[0].tags?.contains("test") == true)
    }

    @Test
    fun `createPost with empty content returns validation error`() = runTest {
        val request = NewPostRequest(content = "", targets = listOf("rss"))

        val result =
            rssModule.createPost(request, testUserUuid)

        assertTrue(result is Either.Left)
        val error = (result as Either.Left).value
        assertEquals(400, error.status)
        assertTrue(error.errorMessage.contains("between 1 and 1000 characters"))
    }

    @Test
    fun `createPost with content too long returns validation error`() = runTest {
        val longContent = "a".repeat(1001)
        val request = NewPostRequest(content = longContent, targets = listOf("rss"))

        val result =
            rssModule.createPost(request, testUserUuid)

        assertTrue(result is Either.Left)
        val error = (result as Either.Left).value
        assertEquals(400, error.status)
    }

    @Test
    fun `createPost with cleanupHtml removes HTML tags`() = runTest {
        val request =
            NewPostRequest(
                content = "<p>Test <strong>content</strong> with &nbsp; HTML</p>",
                targets = listOf("rss"),
                cleanupHtml = true,
            )

        val result =
            rssModule.createPost(request, testUserUuid)

        assertTrue(result is Either.Right)

        val posts = postsDb.getAllForUser(testUserUuid)
        assertTrue(posts is Either.Right)
        val postsList = (posts as Either.Right).value
        assertEquals(1, postsList.size)
        assertEquals("Test content with   HTML", postsList[0].content)
    }

    @Test
    fun `createPost without cleanupHtml preserves HTML`() = runTest {
        val htmlContent = "<p>Test <strong>content</strong></p>"
        val request =
            NewPostRequest(content = htmlContent, targets = listOf("rss"), cleanupHtml = false)

        val result =
            rssModule.createPost(request, testUserUuid)

        assertTrue(result is Either.Right)

        val posts = postsDb.getAllForUser(testUserUuid)
        assertTrue(posts is Either.Right)
        val postsList = (posts as Either.Right).value
        assertEquals(1, postsList.size)
        assertEquals(htmlContent, postsList[0].content)
    }

    @Test
    fun `createPost stores images reference`() = runTest {
        val request =
            NewPostRequest(
                content = "Post with image",
                targets = listOf("rss"),
                images = listOf("image-uuid-1", "image-uuid-2"),
            )

        val result =
            rssModule.createPost(request, testUserUuid)

        assertTrue(result is Either.Right)

        val posts = postsDb.getAllForUser(testUserUuid)
        assertTrue(posts is Either.Right)
        val postsList = (posts as Either.Right).value
        assertEquals(1, postsList.size)
        assertEquals(2, postsList[0].images?.size)
        assertTrue(postsList[0].images?.contains("image-uuid-1") == true)
        assertTrue(postsList[0].images?.contains("image-uuid-2") == true)
    }

    @Test
    fun `generateRss produces valid RSS feed`() = runTest {
        // Create a test post first
        val request = NewPostRequest(content = "Test RSS post", targets = listOf("rss"))
        val createResult =
            rssModule.createPost(request, testUserUuid)
        assertTrue(createResult is Either.Right)

        val rssContent = rssModule.generateRss(testUserUuid)

        assertTrue(rssContent.contains("<?xml"))
        assertTrue(rssContent.contains("<rss"))
        assertTrue(rssContent.contains("Test RSS post"))
        assertTrue(rssContent.contains("localhost:3000"))
    }

    @Test
    fun `generateRss filters by links when filterByLinks is include`() = runTest {
        // Post with link
        val result1 =
            rssModule.createPost(
                NewPostRequest(content = "Post with link", link = "https://example.com"),
                testUserUuid,
            )
        assertTrue(result1 is Either.Right)
        // Post without link
        val result2 =
            rssModule.createPost(
                NewPostRequest(content = "Post without link"),
                testUserUuid,
            )
        assertTrue(result2 is Either.Right)

        val rssContent = rssModule.generateRss(testUserUuid, filterByLinks = "include")

        assertTrue(rssContent.contains("Post with link"))
        assertFalse(rssContent.contains("Post without link"))
    }

    @Test
    fun `generateRss filters by links when filterByLinks is exclude`() = runTest {
        // Post with link
        val result1 =
            rssModule.createPost(
                NewPostRequest(content = "Post with link", link = "https://example.com"),
                testUserUuid,
            )
        assertTrue(result1 is Either.Right)
        // Post without link
        val result2 =
            rssModule.createPost(
                NewPostRequest(content = "Post without link"),
                testUserUuid,
            )
        assertTrue(result2 is Either.Right)

        val rssContent = rssModule.generateRss(testUserUuid, filterByLinks = "exclude")

        assertFalse(rssContent.contains("Post with link"))
        assertTrue(rssContent.contains("Post without link"))
    }

    @Test
    fun `generateRss filters by target`() = runTest {
        val result1 =
            rssModule.createPost(
                NewPostRequest(content = "Twitter post", targets = listOf("twitter")),
                testUserUuid,
            )
        assertTrue(result1 is Either.Right)
        val result2 =
            rssModule.createPost(
                NewPostRequest(content = "Mastodon post", targets = listOf("mastodon")),
                testUserUuid,
            )
        assertTrue(result2 is Either.Right)

        val rssContent = rssModule.generateRss(testUserUuid, target = "twitter")

        assertTrue(rssContent.contains("Twitter post"))
        assertFalse(rssContent.contains("Mastodon post"))
    }

    @Test
    fun `getRssItemByUuid returns post when found`() = runTest {
        val request = NewPostRequest(content = "Test post")
        val result =
            rssModule.createPost(request, testUserUuid)
        assertTrue(result is Either.Right)

        val posts = postsDb.getAllForUser(testUserUuid)
        val uuid = (posts as Either.Right).value[0].uuid

        val post = rssModule.getRssItemByUuid(testUserUuid, uuid)

        assertNotNull(post)
        assertEquals("Test post", post?.content)
    }

    @Test
    fun `getRssItemByUuid returns null when not found`() = runTest {
        val post = rssModule.getRssItemByUuid(testUserUuid, "nonexistent-uuid")

        assertNull(post)
    }
}
