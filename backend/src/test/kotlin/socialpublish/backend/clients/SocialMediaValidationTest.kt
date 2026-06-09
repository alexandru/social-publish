package socialpublish.backend.clients

import arrow.core.nonEmptyListOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import socialpublish.backend.clients.bluesky.BlueskyApiModule
import socialpublish.backend.clients.linkedin.LinkedInApiModule
import socialpublish.backend.clients.linkpreview.LinkPreviewParser
import socialpublish.backend.clients.mastodon.MastodonApiModule
import socialpublish.backend.clients.twitter.TwitterApiModule
import socialpublish.backend.common.NewPostRequest
import socialpublish.backend.common.NewPostRequestMessage
import socialpublish.backend.db.DocumentsDatabase
import socialpublish.backend.testutils.createFilesModule
import socialpublish.backend.testutils.createTestDatabase

class SocialMediaValidationTest {
    private fun testHttpClient(): HttpClient =
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }

    @Test
    fun `bluesky validation uses 300-char limit with links counted as 25`(
        @TempDir tempDir: Path
    ) = runTest {
        val db = createTestDatabase(tempDir)
        val filesModule = createFilesModule(tempDir, db)
        val httpClient = testHttpClient()
        val module =
            BlueskyApiModule(
                filesModule = filesModule,
                httpClient = httpClient,
                linkPreviewParser = LinkPreviewParser(httpClient = httpClient),
            )

        val accepted =
            NewPostRequest(
                messages =
                    nonEmptyListOf(
                        NewPostRequestMessage(
                            content = "a".repeat(273),
                            link = "https://example.com",
                        )
                    )
            )
        val rejected =
            NewPostRequest(
                messages =
                    nonEmptyListOf(
                        NewPostRequestMessage(
                            content = "a".repeat(274),
                            link = "https://example.com",
                        )
                    )
            )

        assertNull(module.validateRequest(accepted))
        val error = module.validateRequest(rejected)
        assertNotNull(error)
        assertEquals("bluesky", error.module)

        httpClient.close()
    }

    @Test
    fun `bluesky validation rejects more than 4 images per message`(
        @TempDir tempDir: Path
    ) = runTest {
        val db = createTestDatabase(tempDir)
        val filesModule = createFilesModule(tempDir, db)
        val httpClient = testHttpClient()
        val module =
            BlueskyApiModule(
                filesModule = filesModule,
                httpClient = httpClient,
                linkPreviewParser = LinkPreviewParser(httpClient = httpClient),
            )

        val fourImages =
            NewPostRequest(
                messages =
                    nonEmptyListOf(
                        NewPostRequestMessage(
                            content = "Hello",
                            images = listOf("img-1", "img-2", "img-3", "img-4"),
                        )
                    )
            )
        val fiveImages =
            NewPostRequest(
                messages =
                    nonEmptyListOf(
                        NewPostRequestMessage(
                            content = "Hello",
                            images =
                                listOf(
                                    "img-1",
                                    "img-2",
                                    "img-3",
                                    "img-4",
                                    "img-5",
                                ),
                        )
                    )
            )

        assertNull(module.validateRequest(fourImages))
        val error = module.validateRequest(fiveImages)
        assertNotNull(error)
        assertEquals("bluesky", error.module)
        assertTrue(error.errorMessage.contains("4 images"))
        assertTrue(error.errorMessage.contains("Post 1"))

        httpClient.close()
    }

    @Test
    fun `mastodon validation uses 500-char limit with links counted as 25`(
        @TempDir tempDir: Path
    ) = runTest {
        val db = createTestDatabase(tempDir)
        val filesModule = createFilesModule(tempDir, db)
        val httpClient = testHttpClient()
        val module = MastodonApiModule(filesModule, httpClient)

        val accepted =
            NewPostRequest(
                messages =
                    nonEmptyListOf(
                        NewPostRequestMessage(
                            content = "a".repeat(473),
                            link = "https://example.com",
                        )
                    )
            )
        val rejected =
            NewPostRequest(
                messages =
                    nonEmptyListOf(
                        NewPostRequestMessage(
                            content = "a".repeat(474),
                            link = "https://example.com",
                        )
                    )
            )

        assertNull(module.validateRequest(accepted))
        val error = module.validateRequest(rejected)
        assertNotNull(error)
        assertEquals("mastodon", error.module)

        httpClient.close()
    }

    @Test
    fun `mastodon validation rejects more than 4 images per message`(
        @TempDir tempDir: Path
    ) = runTest {
        val db = createTestDatabase(tempDir)
        val filesModule = createFilesModule(tempDir, db)
        val httpClient = testHttpClient()
        val module = MastodonApiModule(filesModule, httpClient)

        val fourImages =
            NewPostRequest(
                messages =
                    nonEmptyListOf(
                        NewPostRequestMessage(
                            content = "Hello",
                            images = listOf("img-1", "img-2", "img-3", "img-4"),
                        )
                    )
            )
        val fiveImages =
            NewPostRequest(
                messages =
                    nonEmptyListOf(
                        NewPostRequestMessage(
                            content = "Hello",
                            images =
                                listOf(
                                    "img-1",
                                    "img-2",
                                    "img-3",
                                    "img-4",
                                    "img-5",
                                ),
                        )
                    )
            )

        assertNull(module.validateRequest(fourImages))
        val error = module.validateRequest(fiveImages)
        assertNotNull(error)
        assertEquals("mastodon", error.module)
        assertTrue(error.errorMessage.contains("4 images"))
        assertTrue(error.errorMessage.contains("Post 1"))

        httpClient.close()
    }

    @Test
    fun `twitter validation uses 280-char limit with links counted as 25`(
        @TempDir tempDir: Path
    ) = runTest {
        val db = createTestDatabase(tempDir)
        val filesModule = createFilesModule(tempDir, db)
        val documentsDb = DocumentsDatabase(db)
        val httpClient = testHttpClient()
        val module =
            TwitterApiModule(
                baseUrl = "http://localhost",
                documentsDb = documentsDb,
                filesModule = filesModule,
                httpClient = httpClient,
            )

        val accepted =
            NewPostRequest(
                messages =
                    nonEmptyListOf(
                        NewPostRequestMessage(
                            content = "a".repeat(253),
                            link = "https://example.com",
                        )
                    )
            )
        val rejected =
            NewPostRequest(
                messages =
                    nonEmptyListOf(
                        NewPostRequestMessage(
                            content = "a".repeat(254),
                            link = "https://example.com",
                        )
                    )
            )

        assertNull(module.validateRequest(accepted))
        val error = module.validateRequest(rejected)
        assertNotNull(error)
        assertEquals("twitter", error.module)

        httpClient.close()
    }

    @Test
    fun `twitter validation rejects more than 4 images per message`(
        @TempDir tempDir: Path
    ) = runTest {
        val db = createTestDatabase(tempDir)
        val filesModule = createFilesModule(tempDir, db)
        val documentsDb = DocumentsDatabase(db)
        val httpClient = testHttpClient()
        val module =
            TwitterApiModule(
                baseUrl = "http://localhost",
                documentsDb = documentsDb,
                filesModule = filesModule,
                httpClient = httpClient,
            )

        val fourImages =
            NewPostRequest(
                messages =
                    nonEmptyListOf(
                        NewPostRequestMessage(
                            content = "Hello",
                            images = listOf("img-1", "img-2", "img-3", "img-4"),
                        )
                    )
            )
        val fiveImages =
            NewPostRequest(
                messages =
                    nonEmptyListOf(
                        NewPostRequestMessage(
                            content = "Hello",
                            images =
                                listOf(
                                    "img-1",
                                    "img-2",
                                    "img-3",
                                    "img-4",
                                    "img-5",
                                ),
                        )
                    )
            )

        assertNull(module.validateRequest(fourImages))
        val error = module.validateRequest(fiveImages)
        assertNotNull(error)
        assertEquals("twitter", error.module)
        assertTrue(error.errorMessage.contains("4 images"))
        assertTrue(error.errorMessage.contains("Post 1"))

        httpClient.close()
    }

    @Test
    fun `linkedin validation uses combined content and image limits`(
        @TempDir tempDir: Path
    ) = runTest {
        val db = createTestDatabase(tempDir)
        val filesModule = createFilesModule(tempDir, db)
        val documentsDb = DocumentsDatabase(db)
        val httpClient = testHttpClient()
        val module =
            LinkedInApiModule(
                baseUrl = "http://localhost",
                documentsDb = documentsDb,
                filesModule = filesModule,
                httpClientEngine = httpClient.engine,
                linkPreviewParser = LinkPreviewParser(httpClient = httpClient),
            )

        val accepted =
            NewPostRequest(
                messages =
                    nonEmptyListOf(
                        NewPostRequestMessage(content = "a".repeat(1000)),
                        NewPostRequestMessage(content = "b".repeat(994)),
                    )
            )
        val rejectedCombinedContent =
            NewPostRequest(
                messages =
                    nonEmptyListOf(
                        NewPostRequestMessage(content = "a".repeat(1000)),
                        NewPostRequestMessage(content = "b".repeat(999)),
                    )
            )
        val rejectedCombinedImages =
            NewPostRequest(
                messages =
                    nonEmptyListOf(
                        NewPostRequestMessage(
                            content = "root",
                            images = listOf("image-1", "image-2", "image-3"),
                        ),
                        NewPostRequestMessage(
                            content = "a".repeat(1224),
                            images = listOf("image-4", "image-5"),
                        ),
                    )
            )

        assertNull(module.validateRequest(accepted))
        val contentError = module.validateRequest(rejectedCombinedContent)
        assertNotNull(contentError)
        assertEquals("linkedin", contentError.module)

        val imageError = module.validateRequest(rejectedCombinedImages)
        assertNotNull(imageError)
        assertEquals("linkedin", imageError.module)

        httpClient.close()
    }
}
