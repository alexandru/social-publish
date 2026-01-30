package socialpublish.backend.clients

import arrow.core.Either
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.io.TempDir
import socialpublish.backend.clients.metathreads.MetaThreadsApiModule
import socialpublish.backend.clients.metathreads.MetaThreadsConfig
import socialpublish.backend.common.NewPostRequest
import socialpublish.backend.testutils.createFilesModule
import socialpublish.backend.testutils.createTestDatabase

class MetaThreadsApiTest {
    @Test
    fun `creates post without images`(@TempDir tempDir: Path) = runTest {
        testApplication {
            val jdbi = createTestDatabase(tempDir)
            val filesModule = createFilesModule(tempDir, jdbi)

            application {
                routing {
                    post("/v1.0/test-user-id/threads") {
                        call.respondText(
                            """{"id":"container123"}""",
                            io.ktor.http.ContentType.Application.Json,
                        )
                    }
                    post("/v1.0/test-user-id/threads_publish") {
                        call.respondText(
                            """{"id":"post123"}""",
                            io.ktor.http.ContentType.Application.Json,
                        )
                    }
                }
            }

            val metaThreadsClient = createClient {
                install(ClientContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            isLenient = true
                        }
                    )
                }
            }
            val metaThreadsModule =
                MetaThreadsApiModule(
                    config =
                        MetaThreadsConfig(
                            apiBase = "http://localhost",
                            userId = "test-user-id",
                            accessToken = "test-token",
                        ),
                    filesModule = filesModule,
                    httpClient = metaThreadsClient,
                )

            val req = NewPostRequest(content = "Hello Meta Threads")
            val result = metaThreadsModule.createPost(req)

            assertTrue(result.isRight())
            val response = (result as Either.Right).value
            assertEquals("metathreads", response.module)

            metaThreadsClient.close()
        }
    }

    @Test
    fun `creates post with link`(@TempDir tempDir: Path) = runTest {
        testApplication {
            val jdbi = createTestDatabase(tempDir)
            val filesModule = createFilesModule(tempDir, jdbi)

            application {
                routing {
                    post("/v1.0/test-user-id/threads") {
                        call.respondText(
                            """{"id":"container456"}""",
                            io.ktor.http.ContentType.Application.Json,
                        )
                    }
                    post("/v1.0/test-user-id/threads_publish") {
                        call.respondText(
                            """{"id":"post456"}""",
                            io.ktor.http.ContentType.Application.Json,
                        )
                    }
                }
            }

            val metaThreadsClient = createClient {
                install(ClientContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            isLenient = true
                        }
                    )
                }
            }
            val metaThreadsModule =
                MetaThreadsApiModule(
                    config =
                        MetaThreadsConfig(
                            apiBase = "http://localhost",
                            userId = "test-user-id",
                            accessToken = "test-token",
                        ),
                    filesModule = filesModule,
                    httpClient = metaThreadsClient,
                )

            val req = NewPostRequest(content = "Check out this link", link = "https://example.com")
            val result = metaThreadsModule.createPost(req)

            assertTrue(result.isRight())

            metaThreadsClient.close()
        }
    }

    @Test
    fun `creates post with images`(@TempDir tempDir: Path) = runTest {
        testApplication {
            val jdbi = createTestDatabase(tempDir)
            val filesModule = createFilesModule(tempDir, jdbi)

            application {
                routing {
                    post("/api/files/upload") {
                        call.respondText(
                            """{"uuid":"image-uuid-123","url":"http://localhost/files/image-uuid-123"}""",
                            io.ktor.http.ContentType.Application.Json,
                        )
                    }
                    post("/v1.0/test-user-id/threads") {
                        call.respondText(
                            """{"id":"container789"}""",
                            io.ktor.http.ContentType.Application.Json,
                        )
                    }
                    post("/v1.0/test-user-id/threads_publish") {
                        call.respondText(
                            """{"id":"post789"}""",
                            io.ktor.http.ContentType.Application.Json,
                        )
                    }
                }
            }

            val metaThreadsClient = createClient {
                install(ClientContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            isLenient = true
                        }
                    )
                }
            }
            val metaThreadsModule =
                MetaThreadsApiModule(
                    config =
                        MetaThreadsConfig(
                            apiBase = "http://localhost",
                            userId = "test-user-id",
                            accessToken = "test-token",
                        ),
                    filesModule = filesModule,
                    httpClient = metaThreadsClient,
                )

            val req = NewPostRequest(content = "Post with image", images = listOf("image-uuid-123"))
            val result = metaThreadsModule.createPost(req)

            assertTrue(result.isRight())
            val response = (result as Either.Right).value
            assertEquals("metathreads", response.module)

            metaThreadsClient.close()
        }
    }

    @Test
    fun `refreshes access token`(@TempDir tempDir: Path) = runTest {
        testApplication {
            val jdbi = createTestDatabase(tempDir)
            val filesModule = createFilesModule(tempDir, jdbi)

            application {
                routing {
                    post("/v1.0/access_token") {
                        call.respondText(
                            """{"access_token":"new-token-123","token_type":"bearer","expires_in":5184000}""",
                            io.ktor.http.ContentType.Application.Json,
                        )
                    }
                }
            }

            val metaThreadsClient = createClient {
                install(ClientContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            isLenient = true
                        }
                    )
                }
            }
            val metaThreadsModule =
                MetaThreadsApiModule(
                    config =
                        MetaThreadsConfig(
                            apiBase = "http://localhost",
                            userId = "test-user-id",
                            accessToken = "old-token",
                        ),
                    filesModule = filesModule,
                    httpClient = metaThreadsClient,
                )

            val result = metaThreadsModule.refreshAccessToken()

            assertTrue(result.isRight())
            val response = (result as Either.Right).value
            assertEquals("new-token-123", response.accessToken)
            assertEquals("bearer", response.tokenType)
            assertEquals(5184000L, response.expiresIn)

            metaThreadsClient.close()
        }
    }
}
