package socialpublish.backend.modules

import arrow.core.Either
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import socialpublish.backend.common.ApiError
import socialpublish.backend.common.CompositeError
import socialpublish.backend.common.NewPostRequest
import socialpublish.backend.common.NewPostResponse
import socialpublish.backend.common.NewRssPostResponse
import socialpublish.backend.db.DocumentsDatabase
import socialpublish.backend.db.FilesDatabase
import socialpublish.backend.db.PostsDatabase
import socialpublish.backend.testutils.createTestDatabase

class PublishModuleTest {
    private lateinit var postsDb: PostsDatabase
    private lateinit var filesDb: FilesDatabase
    private lateinit var rssModule: RssModule

    @BeforeEach
    fun setup(@TempDir tempDir: Path) = runTest {
        val db = createTestDatabase(tempDir)
        val documentsDb = DocumentsDatabase(db)
        postsDb = PostsDatabase(documentsDb)
        filesDb = FilesDatabase(db)
        rssModule = RssModule("http://localhost:3000", postsDb, filesDb)
    }

    @Test
    fun `PublishModule can be instantiated`() = runTest {
        val publishModule = PublishModule(null, null, null, null, null, null, null, null, rssModule, java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"))
        assertNotNull(publishModule)
    }

    @Test
    fun `broadcastPost to RSS only returns success`() = runTest {
        val publishModule = PublishModule(null, null, null, null, null, null, null, null, rssModule, java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"))
        val request = NewPostRequest(content = "Test post to RSS", targets = listOf("rss"))

        val result = publishModule.broadcastPost(request)

        val successResult = assertIs<Either.Right<Map<String, NewPostResponse>>>(result)
        val responses = successResult.value
        assertEquals(1, responses.size)
        assertTrue(responses.containsKey("rss"))
        val rssResponse = responses["rss"]
        val typedResponse = assertIs<NewRssPostResponse>(rssResponse)
        assertTrue(typedResponse.uri.contains("http://localhost:3000/rss/"))
    }

    @Test
    fun `broadcastPost to unconfigured platform returns error`() = runTest {
        val publishModule = PublishModule(null, null, null, null, null, null, null, null, rssModule, java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"))
        val request = NewPostRequest(content = "Test post", targets = listOf("mastodon"))

        val result = publishModule.broadcastPost(request)

        val errorResult = assertIs<Either.Left<ApiError>>(result)
        val error = errorResult.value
        val compositeError = assertIs<CompositeError>(error)
        assertEquals(503, compositeError.status)
        assertTrue(compositeError.errorMessage.contains("publish"))
        assertEquals(1, compositeError.responses.size)
        val response = compositeError.responses[0]
        assertEquals("error", response.type)
        assertEquals("publish", response.module)
        assertTrue(response.error?.contains("Mastodon") == true)
        assertTrue(response.error.contains("not configured"))
    }

    @Test
    fun `broadcastPost to multiple targets with mixed results returns composite error`() = runTest {
        val publishModule = PublishModule(null, null, null, null, null, null, null, null, rssModule, java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"))
        val request =
            NewPostRequest(content = "Test post", targets = listOf("rss", "mastodon", "twitter"))

        val result = publishModule.broadcastPost(request)

        val errorResult = assertIs<Either.Left<ApiError>>(result)
        val error = errorResult.value
        val compositeError = assertIs<CompositeError>(error)
        assertEquals(503, compositeError.status)
        assertEquals(3, compositeError.responses.size)

        // RSS should succeed
        val rssResponse = compositeError.responses.find { it.result?.module == "rss" }
        assertNotNull(rssResponse)
        assertEquals("success", rssResponse.type)
        val typedRssResult = assertIs<NewRssPostResponse>(rssResponse.result)
        assertNotNull(typedRssResult)

        // Mastodon should fail
        val mastodonResponse =
            compositeError.responses.find {
                it.module == "publish" && it.error?.contains("Mastodon") == true
            }
        assertNotNull(mastodonResponse)
        assertEquals("error", mastodonResponse.type)

        // Twitter should fail
        val twitterResponse =
            compositeError.responses.find {
                it.module == "publish" && it.error?.contains("Twitter") == true
            }
        assertNotNull(twitterResponse)
        assertEquals("error", twitterResponse.type)
    }

    @Test
    fun `broadcastPost with empty targets returns empty map`() = runTest {
        val publishModule = PublishModule(null, null, null, null, null, null, null, null, rssModule, java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"))
        val request = NewPostRequest(content = "Test post", targets = emptyList())

        val result = publishModule.broadcastPost(request)

        val successResult = assertIs<Either.Right<Map<String, NewPostResponse>>>(result)
        val responses = successResult.value
        assertTrue(responses.isEmpty())
    }

    @Test
    fun `broadcastPost with null targets returns empty map`() = runTest {
        val publishModule = PublishModule(null, null, null, null, null, null, null, null, rssModule, java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"))
        val request = NewPostRequest(content = "Test post", targets = null)

        val result = publishModule.broadcastPost(request)

        val successResult = assertIs<Either.Right<Map<String, NewPostResponse>>>(result)
        val responses = successResult.value
        assertTrue(responses.isEmpty())
    }

    @Test
    fun `broadcastPost normalizes target names to lowercase`() = runTest {
        val publishModule = PublishModule(null, null, null, null, null, null, null, null, rssModule, java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"))
        val request = NewPostRequest(content = "Test post", targets = listOf("RSS", "Mastodon"))

        val result = publishModule.broadcastPost(request)

        // Should process as lowercase (rss succeeds, mastodon fails)
        val errorResult = assertIs<Either.Left<ApiError>>(result)
        val error = errorResult.value
        val compositeError = assertIs<CompositeError>(error)
        assertEquals(2, compositeError.responses.size)
    }

    @Test
    fun `broadcastPost with multiple unconfigured platforms`() = runTest {
        val publishModule = PublishModule(null, null, null, null, null, null, null, null, rssModule, java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"))
        val request =
            NewPostRequest(
                content = "Test post to all platforms",
                targets = listOf("rss", "mastodon", "bluesky", "twitter", "linkedin"),
            )

        val result = publishModule.broadcastPost(request)

        // Should return composite error since 4 platforms are not configured
        val errorResult = assertIs<Either.Left<ApiError>>(result)
        val error = errorResult.value
        val compositeError = assertIs<CompositeError>(error)
        assertEquals(503, compositeError.status)
        assertEquals(5, compositeError.responses.size)

        // RSS should succeed
        val rssResponse = compositeError.responses.find { it.result?.module == "rss" }
        assertNotNull(rssResponse)
        assertEquals("success", rssResponse.type)

        // Others should fail
        val failedCount = compositeError.responses.count { it.type == "error" }
        assertEquals(4, failedCount)
    }
}
