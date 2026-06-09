package socialpublish.backend.modules

import arrow.core.Either
import arrow.core.nonEmptyListOf
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import socialpublish.backend.clients.mastodon.MastodonApiModule
import socialpublish.backend.clients.mastodon.MastodonConfig
import socialpublish.backend.common.ApiError
import socialpublish.backend.common.CompositeError
import socialpublish.backend.common.NewFeedPostResponse
import socialpublish.backend.common.NewPostRequest
import socialpublish.backend.common.NewPostRequestMessage
import socialpublish.backend.common.NewPostResponse
import socialpublish.backend.common.Target
import socialpublish.backend.db.DocumentsDatabase
import socialpublish.backend.db.FilesDatabase
import socialpublish.backend.db.PostsDatabase
import socialpublish.backend.db.UUIDv7
import socialpublish.backend.testutils.createFilesModule
import socialpublish.backend.testutils.createTestDatabase
import socialpublish.backend.testutils.createTestSession

class PublishModuleTest {
    private lateinit var postsDb: PostsDatabase
    private lateinit var filesDb: FilesDatabase
    private lateinit var feedModule: FeedModule
    private val testSession =
        createTestSession(
            UUIDv7.fromString("00000000-0000-0000-0000-000000000001")
        )

    @BeforeEach
    fun setup(@TempDir tempDir: Path) = runTest {
        val db = createTestDatabase(tempDir)
        val documentsDb = DocumentsDatabase(db)
        postsDb = PostsDatabase(documentsDb)
        filesDb = FilesDatabase(db)
        feedModule = FeedModule("http://localhost:3000", postsDb, filesDb)
    }

    @Test
    fun `PublishModule can be instantiated`() = runTest {
        val publishModule =
            PublishModule(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                feedModule,
                testSession,
            )
        assertNotNull(publishModule)
    }

    @Test
    fun `broadcastPost to feed only returns success`() = runTest {
        val publishModule =
            PublishModule(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                feedModule,
                testSession,
            )
        val request =
            NewPostRequest.singleMessage(
                content = "Test post to feed",
                targets = listOf(Target.Feed),
            )

        val result =
            context(testSession) { publishModule.broadcastPost(request) }

        val successResult =
            assertIs<Either.Right<Map<String, NewPostResponse>>>(result)
        val responses = successResult.value
        assertEquals(1, responses.size)
        assertTrue(responses.containsKey("feed"))
        val feedResponse = responses["feed"]
        val typedResponse = assertIs<NewFeedPostResponse>(feedResponse)
        assertTrue(typedResponse.uri.contains("http://localhost:3000/feed/"))
    }

    @Test
    fun `broadcastPost to unconfigured platform returns error`() = runTest {
        val publishModule =
            PublishModule(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                feedModule,
                testSession,
            )
        val request =
            NewPostRequest.singleMessage(
                content = "Test post",
                targets = listOf(Target.Mastodon),
            )

        val result =
            context(testSession) { publishModule.broadcastPost(request) }

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
    fun `broadcastPost to multiple targets with mixed results returns composite error`() =
        runTest {
            val publishModule =
                PublishModule(
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    feedModule,
                    testSession,
                )
            val request =
                NewPostRequest.singleMessage(
                    content = "Test post",
                    targets =
                        listOf(Target.Feed, Target.Mastodon, Target.Twitter),
                )

            val result =
                context(testSession) { publishModule.broadcastPost(request) }

            val errorResult = assertIs<Either.Left<ApiError>>(result)
            val error = errorResult.value
            val compositeError = assertIs<CompositeError>(error)
            assertEquals(503, compositeError.status)
            assertEquals(3, compositeError.responses.size)

            // Feed should succeed
            val feedResponse =
                compositeError.responses.find { it.result?.module == "feed" }
            assertNotNull(feedResponse)
            assertEquals("success", feedResponse.type)
            val typedFeedResult =
                assertIs<NewFeedPostResponse>(feedResponse.result)
            assertNotNull(typedFeedResult)

            // Mastodon should fail
            val mastodonResponse =
                compositeError.responses.find {
                    it.module == "publish" &&
                        it.error?.contains("Mastodon") == true
                }
            assertNotNull(mastodonResponse)
            assertEquals("error", mastodonResponse.type)

            // Twitter should fail
            val twitterResponse =
                compositeError.responses.find {
                    it.module == "publish" &&
                        it.error?.contains("Twitter") == true
                }
            assertNotNull(twitterResponse)
            assertEquals("error", twitterResponse.type)
        }

    @Test
    fun `broadcastPost with empty targets returns validation error`() =
        runTest {
            val publishModule =
                PublishModule(
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    feedModule,
                    testSession,
                )
            val request =
                NewPostRequest.singleMessage(
                    content = "Test post",
                    targets = emptyList(),
                )

            val result =
                context(testSession) { publishModule.broadcastPost(request) }

            val errorResult = assertIs<Either.Left<ApiError>>(result)
            val error = errorResult.value
            assertEquals(400, error.status)
            assertTrue(
                error.errorMessage.contains("No publish targets selected")
            )
        }

    @Test
    fun `broadcastPost with null targets returns validation error`() = runTest {
        val publishModule =
            PublishModule(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                feedModule,
                testSession,
            )
        val request =
            NewPostRequest.singleMessage(content = "Test post", targets = null)

        val result =
            context(testSession) { publishModule.broadcastPost(request) }

        val errorResult = assertIs<Either.Left<ApiError>>(result)
        val error = errorResult.value
        assertEquals(400, error.status)
        assertTrue(error.errorMessage.contains("No publish targets selected"))
    }

    @Test
    fun `broadcastPost normalizes target names to lowercase`() = runTest {
        val publishModule =
            PublishModule(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                feedModule,
                testSession,
            )
        val request =
            NewPostRequest.singleMessage(
                content = "Test post",
                targets = listOf(Target.Feed, Target.Mastodon),
            )

        val result =
            context(testSession) { publishModule.broadcastPost(request) }

        // Should process as lowercase (feed succeeds, mastodon fails)
        val errorResult = assertIs<Either.Left<ApiError>>(result)
        val error = errorResult.value
        val compositeError = assertIs<CompositeError>(error)
        assertEquals(2, compositeError.responses.size)
    }

    @Test
    fun `broadcastPost with multiple unconfigured platforms`() = runTest {
        val publishModule =
            PublishModule(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                feedModule,
                testSession,
            )
        val request =
            NewPostRequest.singleMessage(
                content = "Test post to all platforms",
                targets =
                    listOf(
                        Target.Feed,
                        Target.Mastodon,
                        Target.Bluesky,
                        Target.Twitter,
                        Target.LinkedIn,
                    ),
            )

        val result =
            context(testSession) { publishModule.broadcastPost(request) }

        // Should return composite error since 4 platforms are not configured
        val errorResult = assertIs<Either.Left<ApiError>>(result)
        val error = errorResult.value
        val compositeError = assertIs<CompositeError>(error)
        assertEquals(503, compositeError.status)
        assertEquals(5, compositeError.responses.size)

        // Feed should succeed
        val feedResponse =
            compositeError.responses.find { it.result?.module == "feed" }
        assertNotNull(feedResponse)
        assertEquals("success", feedResponse.type)

        // Others should fail
        val failedCount = compositeError.responses.count { it.type == "error" }
        assertEquals(4, failedCount)
    }

    @Test
    fun `broadcastPost accepts linkedin with more than two messages`() =
        runTest {
            val publishModule =
                PublishModule(
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    feedModule,
                    testSession,
                )

            val request =
                NewPostRequest(
                    targets = listOf(Target.LinkedIn, Target.Feed),
                    messages =
                        nonEmptyListOf(
                            NewPostRequestMessage(content = "Root"),
                            NewPostRequestMessage(content = "Reply #1"),
                            NewPostRequestMessage(content = "Reply #2"),
                        ),
                )

            val result =
                context(testSession) { publishModule.broadcastPost(request) }

            val error = assertIs<Either.Left<ApiError>>(result).value
            // LinkedIn itself is not configured (null), so it surfaces a 503
            // composite error — but preflight must NOT reject the request for
            // having more than two messages (LinkedIn concatenates them).
            val compositeError = assertIs<CompositeError>(error)
            assertTrue(
                compositeError.errorMessage.contains("Failed to publish") ||
                    compositeError.responses.any { it.module == "linkedin" }
            )
            assertTrue(
                compositeError.responses.none {
                    it.type == "error" &&
                        it.error?.contains("two messages") == true
                }
            )
        }

    @Test
    fun `broadcastPost validation failure prevents feed persistence`() =
        runTest {
            val publishModule =
                PublishModule(
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    feedModule,
                    testSession,
                )

            val request =
                NewPostRequest(
                    targets = listOf(Target.LinkedIn, Target.Feed),
                    messages =
                        nonEmptyListOf(
                            NewPostRequestMessage(content = "x".repeat(1001))
                        ),
                )

            val _ =
                context(testSession) { publishModule.broadcastPost(request) }

            val posts =
                postsDb.getAllForUser(
                    UUIDv7.fromString("00000000-0000-0000-0000-000000000001")
                )
            assertTrue(posts.isRight())
            val list = (posts as Either.Right).value
            assertTrue(list.isEmpty())
        }

    @Test
    fun `broadcastPost feed validation failure prevents publishing`() =
        runTest {
            val publishModule =
                PublishModule(
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    feedModule,
                    testSession,
                )

            val longContent = "x".repeat(1001)
            val request =
                NewPostRequest.singleMessage(
                    content = longContent,
                    targets = listOf(Target.Feed),
                )

            val result =
                context(testSession) { publishModule.broadcastPost(request) }

            val errorResult = assertIs<Either.Left<ApiError>>(result)
            val error = errorResult.value
            assertEquals(400, error.status)
            assertTrue(error.errorMessage.contains("characters"))

            // Verify nothing was persisted
            val posts =
                postsDb.getAllForUser(
                    UUIDv7.fromString("00000000-0000-0000-0000-000000000001")
                )
            assertTrue(posts.isRight())
            val list = (posts as Either.Right).value
            assertTrue(list.isEmpty())
        }

    @Test
    fun `broadcastPost maps upstream RequestError status to 502 Bad Gateway`(
        @TempDir tempDir: Path
    ) = runTest {
        testApplication {
            val jdbi = createTestDatabase(tempDir)
            val filesModule = createFilesModule(tempDir, jdbi)
            val upstreamBody = "upstream says no"

            application {
                routing {
                    post("/api/v1/statuses") {
                        call.respondText(
                            upstreamBody,
                            status = HttpStatusCode.Unauthorized,
                        )
                    }
                }
            }

            val mastodonClient = createClient {
                install(ClientContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            isLenient = true
                        }
                    )
                }
            }
            val mastodonModule = MastodonApiModule(filesModule, mastodonClient)
            val mastodonConfig =
                MastodonConfig(host = "http://localhost", accessToken = "token")

            val publishModule =
                PublishModule(
                    mastodonModule,
                    mastodonConfig,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    feedModule,
                    testSession,
                )

            val request =
                NewPostRequest.singleMessage(
                    content = "Test",
                    targets = listOf(Target.Mastodon),
                )

            val result =
                context(testSession) { publishModule.broadcastPost(request) }

            val error = assertIs<Either.Left<ApiError>>(result).value
            val compositeError = assertIs<CompositeError>(error)
            // The upstream returned 401; the publish response must surface a
            // 5xx (502) so the frontend does not mistake this for a
            // Social-Publish session expiry.
            assertEquals(502, compositeError.status)
            val mastodonResponse =
                compositeError.responses.find {
                    it.type == "error" && it.module == "mastodon"
                }
            assertNotNull(mastodonResponse)
            // The user must understand that the failure came from Mastodon,
            // and ideally see what Mastodon said.
            val mastodonError = mastodonResponse.error ?: ""
            assertTrue(mastodonError.contains("Mastodon", ignoreCase = true))
            assertTrue(mastodonError.contains(upstreamBody))

            mastodonClient.close()
        }
    }
}
