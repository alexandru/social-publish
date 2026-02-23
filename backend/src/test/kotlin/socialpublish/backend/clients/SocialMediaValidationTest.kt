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
        HttpClient(CIO) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

    @Test
    fun `bluesky validation uses 300-char limit with links counted as 25`(@TempDir tempDir: Path) =
        runTest {
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
    fun `mastodon validation uses 500-char limit with links counted as 25`(@TempDir tempDir: Path) =
        runTest {
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
    fun `twitter validation uses 280-char limit with links counted as 25`(@TempDir tempDir: Path) =
        runTest {
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
    fun `linkedin validation enforces post and follow-up limits`(@TempDir tempDir: Path) = runTest {
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
                        NewPostRequestMessage(content = "a".repeat(1973), link = "https://x.com"),
                        NewPostRequestMessage(content = "a".repeat(1223), link = "https://x.com"),
                    )
            )
        val rejectedPost =
            NewPostRequest(
                messages =
                    nonEmptyListOf(
                        NewPostRequestMessage(content = "a".repeat(1974), link = "https://x.com")
                    )
            )
        val rejectedFollowUp =
            NewPostRequest(
                messages =
                    nonEmptyListOf(
                        NewPostRequestMessage(content = "root"),
                        NewPostRequestMessage(content = "a".repeat(1224), link = "https://x.com"),
                    )
            )
        val rejectedThreadLength =
            NewPostRequest(
                messages =
                    nonEmptyListOf(
                        NewPostRequestMessage(content = "root"),
                        NewPostRequestMessage(content = "reply one"),
                        NewPostRequestMessage(content = "reply two"),
                    )
            )

        assertNull(module.validateRequest(accepted))
        assertNotNull(module.validateRequest(rejectedPost))
        assertNotNull(module.validateRequest(rejectedFollowUp))
        val threadError = module.validateRequest(rejectedThreadLength)
        assertNotNull(threadError)
        assertEquals("linkedin", threadError.module)

        httpClient.close()
    }
}
