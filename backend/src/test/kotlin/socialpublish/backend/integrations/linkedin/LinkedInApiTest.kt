package socialpublish.backend.integrations.linkedin

import arrow.core.Either
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import socialpublish.backend.models.NewPostRequest
import socialpublish.backend.testutils.createFilesModule
import socialpublish.backend.testutils.createTestDatabase

class LinkedInApiTest {
    @Test
    fun `buildAuthorizeURL generates correct OAuth URL`(@TempDir tempDir: Path) = runTest {
        testApplication {
            val jdbi = createTestDatabase(tempDir)
            val filesModule = createFilesModule(tempDir, jdbi)

            val config =
                LinkedInConfig(clientId = "test-client-id", clientSecret = "test-client-secret")
            val linkedInClient = createClient {
                install(ClientContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            isLenient = true
                        }
                    )
                }
            }

            val module =
                LinkedInApiModule(
                    config,
                    "http://localhost",
                    jdbi.onDemand(socialpublish.backend.db.DocumentsDatabase::class.java),
                    filesModule,
                    linkedInClient.engine,
                )

            val result = module.buildAuthorizeURL("test-jwt-token")

            assertTrue(result is Either.Right)
            val url = (result as Either.Right).value
            assertTrue(url.contains("client_id=test-client-id"))
            assertTrue(url.contains("response_type=code"))
            assertTrue(url.contains("scope=w_member_social"))
        }
    }

    @Test
    fun `exchangeCodeForToken exchanges authorization code for token`(@TempDir tempDir: Path) =
        runTest {
            testApplication {
                val jdbi = createTestDatabase(tempDir)
                val filesModule = createFilesModule(tempDir, jdbi)

                application {
                    routing {
                        post("/oauth/v2/accessToken") {
                            call.respondText(
                                """{"access_token":"test-access-token","expires_in":5184000,"refresh_token":"test-refresh-token","refresh_token_expires_in":31536000}""",
                                io.ktor.http.ContentType.Application.Json,
                            )
                        }
                    }
                }

                val linkedInClient = createClient {
                    install(ClientContentNegotiation) {
                        json(
                            Json {
                                ignoreUnknownKeys = true
                                isLenient = true
                            }
                        )
                    }
                }

                val config =
                    LinkedInConfig(
                        clientId = "test-client-id",
                        clientSecret = "test-client-secret",
                        accessTokenUrl = "http://localhost/oauth/v2/accessToken",
                    )

                val module =
                    LinkedInApiModule(
                        config,
                        "http://localhost",
                        jdbi.onDemand(socialpublish.backend.db.DocumentsDatabase::class.java),
                        filesModule,
                        linkedInClient.engine,
                    )

                val result = module.exchangeCodeForToken("test-code", "http://localhost/callback")

                assertTrue(result is Either.Right)
                val token = (result as Either.Right).value
                assertEquals("test-access-token", token.accessToken)
                assertEquals("test-refresh-token", token.refreshToken)
                assertEquals(5184000L, token.expiresIn)
            }
        }

    @Test
    fun `creates post with OAuth token`(@TempDir tempDir: Path) = runTest {
        testApplication {
            val jdbi = createTestDatabase(tempDir)
            val filesModule = createFilesModule(tempDir, jdbi)
            var postCreated = false

            // Save a mock OAuth token to DB
            val documentsDb = jdbi.onDemand(socialpublish.backend.db.DocumentsDatabase::class.java)
            val token =
                LinkedInOAuthToken(
                    accessToken = "test-access-token",
                    expiresIn = 5184000,
                    refreshToken = "test-refresh-token",
                    refreshTokenExpiresIn = 31536000,
                )
            val _ =
                documentsDb.createOrUpdate(
                    kind = "linkedin-oauth-token",
                    payload = Json.encodeToString(token),
                    searchKey = "linkedin-oauth-token",
                    tags = emptyList(),
                )

            application {
                routing {
                    get("/v2/me") {
                        call.respondText(
                            """{"id":"urn:li:person:test123"}""",
                            io.ktor.http.ContentType.Application.Json,
                        )
                    }
                    post("/v2/ugcPosts") {
                        postCreated = true
                        call.respondText(
                            """{"id":"urn:li:share:12345"}""",
                            io.ktor.http.ContentType.Application.Json,
                            io.ktor.http.HttpStatusCode.Created,
                        )
                    }
                }
            }

            val linkedInClient = createClient {
                install(ClientContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            isLenient = true
                        }
                    )
                }
            }

            val config =
                LinkedInConfig(
                    clientId = "test-client-id",
                    clientSecret = "test-client-secret",
                    apiBase = "http://localhost/v2",
                )

            val module =
                LinkedInApiModule(
                    config,
                    "http://localhost",
                    documentsDb,
                    filesModule,
                    linkedInClient.engine,
                )

            val request =
                NewPostRequest(content = "Test LinkedIn post", targets = listOf("linkedin"))

            val result = module.createPost(request)

            assertTrue(result is Either.Right)
            assertTrue(postCreated)
        }
    }
}
