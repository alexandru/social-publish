package socialpublish.backend.integrations.linkedin

import arrow.core.Either
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import socialpublish.backend.db.DocumentsDatabase
import socialpublish.backend.linkpreview.LinkPreviewParser
import socialpublish.backend.models.NewLinkedInPostResponse
import socialpublish.backend.models.NewPostRequest
import socialpublish.backend.testutils.createFilesModule
import socialpublish.backend.testutils.createTestDatabase
import socialpublish.backend.testutils.uploadTestImage

class LinkedInApiTest {
    @Test
    fun `buildAuthorizeURL generates correct OAuth URL`(@TempDir tempDir: Path) = runTest {
        testApplication {
            val jdbi = createTestDatabase(tempDir)
            val filesModule = createFilesModule(tempDir, jdbi)
            val documentsDb = DocumentsDatabase(jdbi)

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
            val linkPreview = LinkPreviewParser(httpClient = linkedInClient)

            val config =
                LinkedInConfig(clientId = "test-client-id", clientSecret = "test-client-secret")

            val module =
                LinkedInApiModule(
                    config,
                    "http://localhost",
                    documentsDb,
                    filesModule,
                    linkedInClient.engine,
                    linkPreview,
                )

            val result = module.buildAuthorizeURL("test-jwt-token")

            assertTrue(result is Either.Right)
            val url = (result as Either.Right).value
            assertTrue(url.contains("client_id=test-client-id"), "URL should contain client_id")
            assertTrue(url.contains("response_type=code"), "URL should contain response_type=code")
            assertTrue(
                url.contains("scope=w_member_social"),
                "URL should contain w_member_social scope",
            )
            assertTrue(url.contains("redirect_uri="), "URL should contain redirect_uri parameter")

            linkedInClient.close()
        }
    }

    @Test
    fun `exchangeCodeForToken exchanges authorization code for token`(@TempDir tempDir: Path) =
        runTest {
            testApplication {
                val jdbi = createTestDatabase(tempDir)
                val filesModule = createFilesModule(tempDir, jdbi)
                val documentsDb = DocumentsDatabase(jdbi)

                application {
                    routing {
                        post("/oauth/v2/accessToken") {
                            call.respondText(
                                """{"access_token":"test-access-token","expires_in":5184000,"refresh_token":"test-refresh-token","refresh_token_expires_in":31536000}""",
                                ContentType.Application.Json,
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
                val linkPreview = LinkPreviewParser(httpClient = linkedInClient)

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
                        documentsDb,
                        filesModule,
                        linkedInClient.engine,
                        linkPreview,
                    )

                val result = module.exchangeCodeForToken("test-code", "http://localhost/callback")

                assertTrue(result is Either.Right)
                val token = (result as Either.Right).value
                assertEquals("test-access-token", token.accessToken)
                assertEquals("test-refresh-token", token.refreshToken)
                assertEquals(5184000L, token.expiresIn)

                linkedInClient.close()
            }
        }

    @Test
    fun `refreshAccessToken refreshes expired token`(@TempDir tempDir: Path) = runTest {
        testApplication {
            val jdbi = createTestDatabase(tempDir)
            val filesModule = createFilesModule(tempDir, jdbi)
            val documentsDb = DocumentsDatabase(jdbi)

            application {
                routing {
                    post("/oauth/v2/accessToken") {
                        call.respondText(
                            """{"access_token":"new-access-token","expires_in":5184000,"refresh_token":"new-refresh-token","refresh_token_expires_in":31536000}""",
                            ContentType.Application.Json,
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
            val linkPreview = LinkPreviewParser(httpClient = linkedInClient)

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
                    documentsDb,
                    filesModule,
                    linkedInClient.engine,
                    linkPreview,
                )

            val result = module.refreshAccessToken("old-refresh-token")

            assertTrue(result is Either.Right)
            val token = (result as Either.Right).value
            assertEquals("new-access-token", token.accessToken)
            assertEquals("new-refresh-token", token.refreshToken)

            linkedInClient.close()
        }
    }

    @Test
    fun `creates text-only post`(@TempDir tempDir: Path) = runTest {
        testApplication {
            val jdbi = createTestDatabase(tempDir)
            val filesModule = createFilesModule(tempDir, jdbi)
            val documentsDb = DocumentsDatabase(jdbi)
            var postCreated = false

            // Save a mock OAuth token to DB
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
                        call.respondText("""{"sub":"test123"}""", ContentType.Application.Json)
                    }
                    post("/v2/ugcPosts") {
                        postCreated = true
                        call.respondText(
                            """{"id":"urn:li:share:12345"}""",
                            ContentType.Application.Json,
                            HttpStatusCode.Created,
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
            val linkPreview = LinkPreviewParser(httpClient = linkedInClient)

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
                    linkPreview,
                )

            val request =
                NewPostRequest(content = "Test LinkedIn post", targets = listOf("linkedin"))

            val result = module.createPost(request)

            assertTrue(result is Either.Right)
            assertTrue(postCreated, "Post should have been created")
            val response = (result as Either.Right).value as NewLinkedInPostResponse
            assertEquals("linkedin", response.module)
            assertNotNull(response.postId)

            linkedInClient.close()
        }
    }

    @Test
    fun `creates post with images`(@TempDir tempDir: Path) = runTest {
        testApplication {
            val jdbi = createTestDatabase(tempDir)
            val filesModule = createFilesModule(tempDir, jdbi)
            val documentsDb = DocumentsDatabase(jdbi)
            var uploadRegistered = false
            var binaryUploaded = false
            var postCreated = false

            // Save a mock OAuth token to DB
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
                    post("/api/files/upload") {
                        val result = filesModule.uploadFile(call)
                        when (result) {
                            is Either.Right ->
                                call.respondText(
                                    Json.encodeToString(result.value),
                                    ContentType.Application.Json,
                                )
                            is Either.Left ->
                                call.respondText(
                                    """{"error":"${result.value.errorMessage}"}""",
                                    ContentType.Application.Json,
                                    HttpStatusCode.fromValue(result.value.status),
                                )
                        }
                    }
                    get("/v2/me") {
                        call.respondText("""{"sub":"test123"}""", ContentType.Application.Json)
                    }
                    post("/v2/assets") {
                        uploadRegistered = true
                        call.respondText(
                            """{"value":{"asset":"urn:li:digitalmediaAsset:test","uploadMechanism":{"com.linkedin.digitalmedia.uploading.MediaUploadHttpRequest":{"uploadUrl":"http://localhost/upload-test"}}}}""",
                            ContentType.Application.Json,
                        )
                    }
                    put("/upload-test") {
                        binaryUploaded = true
                        call.respondText("", status = HttpStatusCode.Created)
                    }
                    post("/v2/ugcPosts") {
                        postCreated = true
                        call.respondText(
                            """{"id":"urn:li:share:12345"}""",
                            ContentType.Application.Json,
                            HttpStatusCode.Created,
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
            val linkPreview = LinkPreviewParser(httpClient = linkedInClient)

            val upload = uploadTestImage(linkedInClient, "flower1.jpeg", "test")

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
                    linkPreview,
                )

            val request =
                NewPostRequest(
                    content = "Post with image",
                    targets = listOf("linkedin"),
                    images = listOf(upload.uuid),
                )

            val result = module.createPost(request)

            assertTrue(result is Either.Right)
            assertTrue(uploadRegistered, "Upload should have been registered")
            assertTrue(binaryUploaded, "Binary should have been uploaded")
            assertTrue(postCreated, "Post should have been created")

            linkedInClient.close()
        }
    }

    @Test
    fun `validates empty content`(@TempDir tempDir: Path) = runTest {
        testApplication {
            val jdbi = createTestDatabase(tempDir)
            val filesModule = createFilesModule(tempDir, jdbi)
            val documentsDb = DocumentsDatabase(jdbi)

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
            val linkPreview = LinkPreviewParser(httpClient = linkedInClient)

            val config =
                LinkedInConfig(clientId = "test-client-id", clientSecret = "test-client-secret")

            val module =
                LinkedInApiModule(
                    config,
                    "http://localhost",
                    documentsDb,
                    filesModule,
                    linkedInClient.engine,
                    linkPreview,
                )

            val request = NewPostRequest(content = "", targets = listOf("linkedin"))

            val result = module.createPost(request)

            assertTrue(result is Either.Left)
            val error = (result as Either.Left).value
            assertEquals(400, error.status)

            linkedInClient.close()
        }
    }

    @Test
    fun `hasLinkedInAuth returns true when token exists`(@TempDir tempDir: Path) = runTest {
        testApplication {
            val jdbi = createTestDatabase(tempDir)
            val filesModule = createFilesModule(tempDir, jdbi)
            val documentsDb = DocumentsDatabase(jdbi)

            // Save a mock OAuth token to DB
            val token = LinkedInOAuthToken(accessToken = "test-access-token", expiresIn = 5184000)
            val _ =
                documentsDb.createOrUpdate(
                    kind = "linkedin-oauth-token",
                    payload = Json.encodeToString(token),
                    searchKey = "linkedin-oauth-token",
                    tags = emptyList(),
                )

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
            val linkPreview = LinkPreviewParser(httpClient = linkedInClient)

            val config = LinkedInConfig(clientId = "test-client-id", clientSecret = "test-secret")

            val module =
                LinkedInApiModule(
                    config,
                    "http://localhost",
                    documentsDb,
                    filesModule,
                    linkedInClient.engine,
                    linkPreview,
                )

            val hasAuth = module.hasLinkedInAuth()

            assertTrue(hasAuth)

            linkedInClient.close()
        }
    }

    @Test
    fun `hasLinkedInAuth returns false when no token exists`(@TempDir tempDir: Path) = runTest {
        testApplication {
            val jdbi = createTestDatabase(tempDir)
            val filesModule = createFilesModule(tempDir, jdbi)
            val documentsDb = DocumentsDatabase(jdbi)

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
            val linkPreview = LinkPreviewParser(httpClient = linkedInClient)

            val config = LinkedInConfig(clientId = "test-client-id", clientSecret = "test-secret")

            val module =
                LinkedInApiModule(
                    config,
                    "http://localhost",
                    documentsDb,
                    filesModule,
                    linkedInClient.engine,
                    linkPreview,
                )

            val hasAuth = module.hasLinkedInAuth()

            assertFalse(hasAuth)

            linkedInClient.close()
        }
    }

    @Test
    fun `getUserProfile retrieves person URN`(@TempDir tempDir: Path) = runTest {
        testApplication {
            val jdbi = createTestDatabase(tempDir)
            val filesModule = createFilesModule(tempDir, jdbi)
            val documentsDb = DocumentsDatabase(jdbi)

            application {
                routing {
                    get("/v2/me") {
                        call.respondText(
                            """{"sub":"testperson123"}""",
                            ContentType.Application.Json,
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
            val linkPreview = LinkPreviewParser(httpClient = linkedInClient)

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
                    linkPreview,
                )

            val result = module.getUserProfile("test-access-token")

            assertTrue(result is Either.Right)
            val profile = (result as Either.Right).value
            assertEquals("testperson123", profile.sub)

            linkedInClient.close()
        }
    }
}
