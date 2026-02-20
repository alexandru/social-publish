package socialpublish.backend.modules

import arrow.core.Either
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import socialpublish.backend.common.NewFeedPostResponse
import socialpublish.backend.common.NewPostRequest
import socialpublish.backend.common.NewPostRequestMessage
import socialpublish.backend.db.DocumentsDatabase
import socialpublish.backend.db.FilesDatabase
import socialpublish.backend.db.PostsDatabase
import socialpublish.backend.testutils.createTestDatabase

class FeedModuleTest {
    private lateinit var feedModule: FeedModule
    private lateinit var postsDb: PostsDatabase
    private lateinit var filesDb: FilesDatabase
    private val testUserUuid = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001")

    @BeforeEach
    fun setup(@TempDir tempDir: Path) = runTest {
        val db = createTestDatabase(tempDir)
        val documentsDb = DocumentsDatabase(db)
        postsDb = PostsDatabase(documentsDb)
        filesDb = FilesDatabase(db)
        feedModule = FeedModule("http://localhost:3000", postsDb, filesDb)
    }

    @Test
    fun `createPost creates valid feed post with tags`() = runTest {
        val request =
            NewPostRequest(
                content = "Test post #kotlin #testing",
                targets = listOf("feed"),
                link = "https://example.com",
            )

        val result = feedModule.createPost(request, testUserUuid)

        assertTrue(result is Either.Right)
        val response = (result as Either.Right).value
        assertTrue(response is NewFeedPostResponse)
        val feedResponse = response as NewFeedPostResponse
        assertTrue(feedResponse.uri.startsWith("http://localhost:3000/feed/"))
    }

    @Test
    fun `createPost extracts hashtags correctly`() = runTest {
        val request =
            NewPostRequest(
                content = "Post with #kotlin #programming #test",
                targets = listOf("feed"),
            )

        val result = feedModule.createPost(request, testUserUuid)

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
        val request = NewPostRequest(content = "", targets = listOf("feed"))

        val result = feedModule.createPost(request, testUserUuid)

        assertTrue(result is Either.Left)
        val error = (result as Either.Left).value
        assertEquals(400, error.status)
        assertTrue(error.errorMessage.contains("between 1 and 1000 characters"))
    }

    @Test
    fun `createPost with content too long returns validation error`() = runTest {
        val longContent = "a".repeat(1001)
        val request = NewPostRequest(content = longContent, targets = listOf("feed"))

        val result = feedModule.createPost(request, testUserUuid)

        assertTrue(result is Either.Left)
        val error = (result as Either.Left).value
        assertEquals(400, error.status)
    }

    @Test
    fun `createPost keeps HTML content as-is`() = runTest {
        val request =
            NewPostRequest(
                content = "<p>Test <strong>content</strong> with &nbsp; HTML</p>",
                targets = listOf("feed"),
            )

        val result = feedModule.createPost(request, testUserUuid)

        assertTrue(result is Either.Right)

        val posts = postsDb.getAllForUser(testUserUuid)
        assertTrue(posts is Either.Right)
        val postsList = (posts as Either.Right).value
        assertEquals(1, postsList.size)
        assertEquals("<p>Test <strong>content</strong> with &nbsp; HTML</p>", postsList[0].content)
    }

    @Test
    fun `createPost stores images reference`() = runTest {
        val request =
            NewPostRequest(
                content = "Post with image",
                targets = listOf("feed"),
                images = listOf("image-uuid-1", "image-uuid-2"),
            )

        val result = feedModule.createPost(request, testUserUuid)

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
    fun `generateFeed produces valid Atom feed`() = runTest {
        // Create a test post first
        val request = NewPostRequest(content = "Test feed post", targets = listOf("feed"))
        val createResult = feedModule.createPost(request, testUserUuid)
        assertTrue(createResult is Either.Right)

        val feedContent = feedModule.generateFeed(testUserUuid)

        assertTrue(feedContent.contains("<?xml"))
        assertTrue(feedContent.contains("<feed"))
        assertTrue(feedContent.contains("Test feed post"))
        assertTrue(feedContent.contains("localhost:3000"))
    }

    @Test
    fun `generateFeed item link for local posts includes user UUID`() = runTest {
        val result =
            feedModule.createPost(NewPostRequest(content = "No external link"), testUserUuid)
        assertTrue(result is Either.Right)

        val posts = postsDb.getAllForUser(testUserUuid)
        assertTrue(posts is Either.Right)
        val postUuid = (posts as Either.Right).value.first().uuid

        val feedContent = feedModule.generateFeed(testUserUuid)
        assertTrue(feedContent.contains("http://localhost:3000/feed/$testUserUuid/$postUuid"))
    }

    @Test
    fun `generateFeed includes thr in-reply-to for threaded messages`() = runTest {
        val request =
            NewPostRequest(
                targets = listOf("feed"),
                messages =
                    listOf(
                        NewPostRequestMessage(content = "Root post"),
                        NewPostRequestMessage(content = "Reply post"),
                    ),
            )
        val result = feedModule.createPost(request, testUserUuid)
        assertTrue(result is Either.Right)

        val feedContent = feedModule.generateFeed(testUserUuid)
        assertTrue(feedContent.contains("thr:in-reply-to"))
    }

    @Test
    fun `generateFeed keeps thread root before reply`() = runTest {
        val request =
            NewPostRequest(
                targets = listOf("feed"),
                messages =
                    listOf(
                        NewPostRequestMessage(content = "Root post in order"),
                        NewPostRequestMessage(content = "Reply post in order"),
                    ),
            )
        val result = feedModule.createPost(request, testUserUuid)
        assertTrue(result is Either.Right)

        val feedContent = feedModule.generateFeed(testUserUuid)
        val rootIndex = feedContent.indexOf("Root post in order")
        val replyIndex = feedContent.indexOf("Reply post in order")

        assertTrue(rootIndex >= 0)
        assertTrue(replyIndex >= 0)
        assertTrue(rootIndex < replyIndex)
    }

    @Test
    fun `createPosts returns typed error when database is unavailable`(@TempDir tempDir: Path) =
        runTest {
            val db = createTestDatabase(tempDir)
            val module =
                FeedModule("http://localhost:3000", PostsDatabase(DocumentsDatabase(db)), filesDb)
            (db.dataSource as? AutoCloseable)?.close()

            val result =
                module.createPosts(
                    targets = listOf("feed"),
                    language = null,
                    messages = listOf(NewPostRequestMessage(content = "will fail")),
                    userUuid = testUserUuid,
                )

            assertTrue(result is Either.Left)
            val error = (result as Either.Left).value
            assertEquals(500, error.status)
            assertEquals("feed", error.module)
        }

    @Test
    fun `generateFeed filters by links when filterByLinks is include`() = runTest {
        // Post with link
        val result1 =
            feedModule.createPost(
                NewPostRequest(content = "Post with link", link = "https://example.com"),
                testUserUuid,
            )
        assertTrue(result1 is Either.Right)
        // Post without link
        val result2 =
            feedModule.createPost(NewPostRequest(content = "Post without link"), testUserUuid)
        assertTrue(result2 is Either.Right)

        val feedContent = feedModule.generateFeed(testUserUuid, filterByLinks = "include")

        assertTrue(feedContent.contains("Post with link"))
        assertFalse(feedContent.contains("Post without link"))
    }

    @Test
    fun `generateFeed filters by links when filterByLinks is exclude`() = runTest {
        // Post with link
        val result1 =
            feedModule.createPost(
                NewPostRequest(content = "Post with link", link = "https://example.com"),
                testUserUuid,
            )
        assertTrue(result1 is Either.Right)
        // Post without link
        val result2 =
            feedModule.createPost(NewPostRequest(content = "Post without link"), testUserUuid)
        assertTrue(result2 is Either.Right)

        val feedContent = feedModule.generateFeed(testUserUuid, filterByLinks = "exclude")

        assertFalse(feedContent.contains("Post with link"))
        assertTrue(feedContent.contains("Post without link"))
    }

    @Test
    fun `generateFeed filters by target`() = runTest {
        val result1 =
            feedModule.createPost(
                NewPostRequest(content = "Twitter post", targets = listOf("twitter")),
                testUserUuid,
            )
        assertTrue(result1 is Either.Right)
        val result2 =
            feedModule.createPost(
                NewPostRequest(content = "Mastodon post", targets = listOf("mastodon")),
                testUserUuid,
            )
        assertTrue(result2 is Either.Right)

        val feedContent = feedModule.generateFeed(testUserUuid, target = "twitter")

        assertTrue(feedContent.contains("Twitter post"))
        assertFalse(feedContent.contains("Mastodon post"))
    }

    @Test
    fun `getFeedItemByUuid returns post when found`() = runTest {
        val request = NewPostRequest(content = "Test post")
        val result = feedModule.createPost(request, testUserUuid)
        assertTrue(result is Either.Right)

        val posts = postsDb.getAllForUser(testUserUuid)
        val uuid = (posts as Either.Right).value[0].uuid

        val post = feedModule.getFeedItemByUuid(testUserUuid, uuid)

        assertNotNull(post)
        assertEquals("Test post", post?.content)
    }

    @Test
    fun `getFeedItemByUuid returns null when not found`() = runTest {
        val post = feedModule.getFeedItemByUuid(testUserUuid, "nonexistent-uuid")

        assertNull(post)
    }
}
