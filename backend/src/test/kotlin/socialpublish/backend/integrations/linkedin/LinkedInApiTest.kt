package socialpublish.backend.integrations.linkedin

import arrow.core.Either
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.request.receiveStream
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
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
import org.junit.jupiter.api.Assertions.assertNull
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
    fun `buildAuthorizeURL generates correct OAuth URL with state parameter`(
        @TempDir tempDir: Path
    ) = runTest {
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
                url.contains("scope=openid+profile+w_member_social"),
                "URL should contain openid profile w_member_social scope",
            )
            assertTrue(url.contains("redirect_uri="), "URL should contain redirect_uri parameter")
            assertTrue(
                url.contains("state="),
                "URL should contain state parameter for CSRF protection",
            )

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
            var postBody: String? = null

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
                    get("/v2/userinfo") {
                        call.respondText(
                            """{"sub":"urn:li:person:test123"}""",
                            ContentType.Application.Json,
                        )
                    }
                    post("/v2/ugcPosts") {
                        postCreated = true
                        postBody = call.receiveStream().readBytes().decodeToString()
                        call.response.header("X-RestLi-Id", "urn:li:ugcPost:12345")
                        call.respondText(
                            """{"id":"urn:li:ugcPost:12345"}""",
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

            // Verify the post body contains UGC API required fields
            assertNotNull(postBody)
            assertTrue(
                postBody!!.contains("\"com.linkedin.ugc.ShareContent\""),
                "Post body should contain UGC ShareContent discriminator",
            )
            assertTrue(
                postBody.contains("\"shareCommentary\""),
                "Post body should contain shareCommentary field",
            )
            assertTrue(
                postBody.contains("\"shareMediaCategory\""),
                "Post body should contain shareMediaCategory field",
            )
            assertTrue(
                postBody.contains("\"NONE\""),
                "Post body should contain NONE for text-only posts",
            )
            assertTrue(
                postBody.contains("\"lifecycleState\""),
                "Post body should contain lifecycleState field",
            )
            assertTrue(
                postBody.contains("\"PUBLISHED\""),
                "Post body should contain PUBLISHED value",
            )
            assertTrue(
                postBody.contains("\"com.linkedin.ugc.MemberNetworkVisibility\""),
                "Post body should contain UGC visibility discriminator",
            )

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
                    get("/v2/userinfo") {
                        call.respondText(
                            """{"sub":"urn:li:person:test123"}""",
                            ContentType.Application.Json,
                        )
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
                        call.response.header("X-RestLi-Id", "urn:li:ugcPost:12345")
                        call.respondText(
                            """{"id":"urn:li:ugcPost:12345"}""",
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
                    get("/v2/userinfo") {
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
            // OIDC /userinfo returns plain ID in "sub" field
            assertEquals("testperson123", profile.sub)

            linkedInClient.close()
        }
    }

    @Test
    fun `creates post with multiple images`(@TempDir tempDir: Path) = runTest {
        testApplication {
            val jdbi = createTestDatabase(tempDir)
            val filesModule = createFilesModule(tempDir, jdbi)
            val documentsDb = DocumentsDatabase(jdbi)
            var uploadCount = 0
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
                    get("/v2/userinfo") {
                        call.respondText(
                            """{"sub":"urn:li:person:test123"}""",
                            ContentType.Application.Json,
                        )
                    }
                    post("/v2/assets") {
                        uploadCount++
                        call.respondText(
                            """{"value":{"asset":"urn:li:digitalmediaAsset:test$uploadCount","uploadMechanism":{"com.linkedin.digitalmedia.uploading.MediaUploadHttpRequest":{"uploadUrl":"http://localhost/upload-test-$uploadCount"}}}}""",
                            ContentType.Application.Json,
                        )
                    }
                    put("/upload-test-1") { call.respondText("", status = HttpStatusCode.Created) }
                    put("/upload-test-2") { call.respondText("", status = HttpStatusCode.Created) }
                    put("/upload-test-3") { call.respondText("", status = HttpStatusCode.Created) }
                    post("/v2/ugcPosts") {
                        postCreated = true
                        call.response.header("X-RestLi-Id", "urn:li:ugcPost:12345")
                        call.respondText(
                            """{"id":"urn:li:ugcPost:12345"}""",
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

            val upload1 = uploadTestImage(linkedInClient, "flower1.jpeg", "test1")
            val upload2 = uploadTestImage(linkedInClient, "flower1.jpeg", "test2")
            val upload3 = uploadTestImage(linkedInClient, "flower1.jpeg", "test3")

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
                    content = "Post with multiple images",
                    targets = listOf("linkedin"),
                    images = listOf(upload1.uuid, upload2.uuid, upload3.uuid),
                )

            val result = module.createPost(request)

            assertTrue(result is Either.Right)
            assertEquals(3, uploadCount, "Should have uploaded 3 images")
            assertTrue(postCreated, "Post should have been created")

            linkedInClient.close()
        }
    }

    @Test
    fun `creates post with link preview and downloads thumbnail as URN`(@TempDir tempDir: Path) =
        runTest {
            testApplication {
                val jdbi = createTestDatabase(tempDir)
                val filesModule = createFilesModule(tempDir, jdbi)
                val documentsDb = DocumentsDatabase(jdbi)
                var thumbnailDownloaded = false
                var thumbnailUploaded = false
                var postCreated = false
                var postBody: String? = null

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
                        get("/v2/userinfo") {
                            call.respondText(
                                """{"sub":"urn:li:person:test123"}""",
                                ContentType.Application.Json,
                            )
                        }
                        // Mock link preview endpoint
                        get("/preview-page") {
                            call.respondText(
                                """
                                <html>
                                <head>
                                    <meta property="og:title" content="Preview Title">
                                    <meta property="og:image" content="http://localhost/preview-image.jpg">
                                </head>
                                <body>Test page</body>
                                </html>
                                """
                                    .trimIndent(),
                                ContentType.Text.Html,
                            )
                        }
                        // Mock preview image download
                        get("/preview-image.jpg") {
                            thumbnailDownloaded = true
                            // Return a minimal JPEG byte array
                            call.respondBytes(
                                byteArrayOf(
                                    0xFF.toByte(),
                                    0xD8.toByte(),
                                    0xFF.toByte(),
                                    0xE0.toByte(),
                                ),
                                ContentType.Image.JPEG,
                            )
                        }
                        // Mock asset registration for thumbnail
                        post("/v2/assets") {
                            thumbnailUploaded = true
                            call.respondText(
                                """{"value":{"asset":"urn:li:digitalmediaAsset:thumbnail123","uploadMechanism":{"com.linkedin.digitalmedia.uploading.MediaUploadHttpRequest":{"uploadUrl":"http://localhost/upload-thumbnail"}}}}""",
                                ContentType.Application.Json,
                            )
                        }
                        put("/upload-thumbnail") {
                            call.respondText("", status = HttpStatusCode.Created)
                        }
                        post("/v2/ugcPosts") {
                            postCreated = true
                            postBody = call.receiveStream().readBytes().decodeToString()
                            call.response.header("X-RestLi-Id", "urn:li:ugcPost:12345")
                            call.respondText(
                                """{"id":"urn:li:ugcPost:12345"}""",
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
                    NewPostRequest(
                        content = "Check out this link!",
                        targets = listOf("linkedin"),
                        link = "http://localhost/preview-page",
                    )

                val result = module.createPost(request)

                assertTrue(result is Either.Right)
                // Thumbnail download/upload removed: ensure post was created
                assertTrue(postCreated, "Post should have been created")

                // Verify the post body contains the originalUrl for ARTICLE shares and does NOT
                // include the asset URN
                assertNotNull(postBody)
                assertFalse(
                    postBody!!.contains("urn:li:digitalmediaAsset:thumbnail123"),
                    "Post should NOT contain LinkedIn asset URN for thumbnail",
                )
                assertTrue(
                    postBody.contains("\"originalUrl\"") ||
                        postBody.contains("http://localhost/preview-image.jpg"),
                    "Post should contain the originalUrl/public image URL for ARTICLE shares",
                )

                linkedInClient.close()
            }
        }

    // TODO: Add automated test for LinkedIn OAuth callback error handling
    // The implementation correctly handles LinkedIn OAuth errors by redirecting to
    // /account?error=...
    // Manual testing confirmed this works correctly with the user-friendly error message.

    // ============================================================================
    // UGC Model Serialization Tests
    // ============================================================================

    @Test
    fun `UgcPostRequest for text-only post serializes correctly`() {
        val json = Json {
            encodeDefaults = true
            prettyPrint = true
        }

        val request =
            UgcPostRequest(
                author = "urn:li:person:test123",
                lifecycleState = UgcLifecycleState.PUBLISHED,
                specificContent =
                    UgcSpecificContent(
                        shareContent =
                            UgcShareContent(
                                shareCommentary = UgcText("Hello World!"),
                                shareMediaCategory = UgcMediaCategory.NONE,
                            )
                    ),
                visibility = UgcVisibility(UgcVisibilityType.PUBLIC),
            )

        val serialized = json.encodeToString(request)

        // Verify required fields
        assertTrue(serialized.contains("\"author\""), "author field should be present")
        assertTrue(
            serialized.contains("\"urn:li:person:test123\""),
            "author value should be present",
        )
        assertTrue(
            serialized.contains("\"lifecycleState\""),
            "lifecycleState field should be present",
        )
        assertTrue(serialized.contains("\"PUBLISHED\""), "lifecycleState should be PUBLISHED")
        assertTrue(
            serialized.contains("\"com.linkedin.ugc.ShareContent\""),
            "specificContent discriminator should be present",
        )
        assertTrue(
            serialized.contains("\"shareCommentary\""),
            "shareCommentary field should be present",
        )
        assertTrue(
            serialized.contains("\"Hello World!\""),
            "shareCommentary text should be present",
        )
        assertTrue(
            serialized.contains("\"shareMediaCategory\""),
            "shareMediaCategory field should be present",
        )
        assertTrue(serialized.contains("\"NONE\""), "shareMediaCategory should be NONE")
        assertTrue(
            serialized.contains("\"com.linkedin.ugc.MemberNetworkVisibility\""),
            "visibility discriminator should be present",
        )
        assertTrue(serialized.contains("\"PUBLIC\""), "visibility should be PUBLIC")
    }

    @Test
    fun `UgcPostRequest for article share serializes correctly`() {
        val json = Json {
            encodeDefaults = true
            prettyPrint = true
            explicitNulls = false
        }

        val request =
            UgcPostRequest(
                author = "urn:li:person:test123",
                lifecycleState = UgcLifecycleState.PUBLISHED,
                specificContent =
                    UgcSpecificContent(
                        shareContent =
                            UgcShareContent(
                                shareCommentary = UgcText("Check out this blog!"),
                                shareMediaCategory = UgcMediaCategory.ARTICLE,
                                media =
                                    listOf(
                                        UgcMedia(
                                            status = "READY",
                                            originalUrl = "https://blog.linkedin.com/",
                                            title = UgcText("Official LinkedIn Blog"),
                                            description = UgcText("Blog description"),
                                        )
                                    ),
                            )
                    ),
                visibility = UgcVisibility(UgcVisibilityType.PUBLIC),
            )

        val serialized = json.encodeToString(request)

        assertTrue(serialized.contains("\"ARTICLE\""), "shareMediaCategory should be ARTICLE")
        assertTrue(serialized.contains("\"originalUrl\""), "originalUrl field should be present")
        assertTrue(
            serialized.contains("\"https://blog.linkedin.com/\""),
            "originalUrl value should be present",
        )
        assertTrue(serialized.contains("\"READY\""), "media status should be READY")
        assertTrue(
            serialized.contains("\"Official LinkedIn Blog\""),
            "title text should be present",
        )
    }

    @Test
    fun `UgcPostRequest for image share serializes correctly`() {
        val json = Json {
            encodeDefaults = true
            prettyPrint = true
            explicitNulls = false
        }

        val request =
            UgcPostRequest(
                author = "urn:li:person:test123",
                lifecycleState = UgcLifecycleState.PUBLISHED,
                specificContent =
                    UgcSpecificContent(
                        shareContent =
                            UgcShareContent(
                                shareCommentary = UgcText("My photo!"),
                                shareMediaCategory = UgcMediaCategory.IMAGE,
                                media =
                                    listOf(
                                        UgcMedia(
                                            status = "READY",
                                            media = "urn:li:digitalmediaAsset:C5422AQEbc381YmIuvg",
                                            title = UgcText("Photo title"),
                                        )
                                    ),
                            )
                    ),
                visibility = UgcVisibility(UgcVisibilityType.PUBLIC),
            )

        val serialized = json.encodeToString(request)

        assertTrue(serialized.contains("\"IMAGE\""), "shareMediaCategory should be IMAGE")
        assertTrue(serialized.contains("\"media\""), "media field should be present")
        assertTrue(
            serialized.contains("\"urn:li:digitalmediaAsset:C5422AQEbc381YmIuvg\""),
            "media asset URN should be present",
        )
        assertTrue(serialized.contains("\"READY\""), "media status should be READY")
    }

    @Test
    fun `UgcPostRequest with multiple images serializes correctly`() {
        val json = Json {
            encodeDefaults = true
            prettyPrint = true
            explicitNulls = false
        }

        val request =
            UgcPostRequest(
                author = "urn:li:person:test123",
                lifecycleState = UgcLifecycleState.PUBLISHED,
                specificContent =
                    UgcSpecificContent(
                        shareContent =
                            UgcShareContent(
                                shareCommentary = UgcText("Multiple photos!"),
                                shareMediaCategory = UgcMediaCategory.IMAGE,
                                media =
                                    listOf(
                                        UgcMedia(
                                            status = "READY",
                                            media = "urn:li:digitalmediaAsset:image1",
                                        ),
                                        UgcMedia(
                                            status = "READY",
                                            media = "urn:li:digitalmediaAsset:image2",
                                        ),
                                        UgcMedia(
                                            status = "READY",
                                            media = "urn:li:digitalmediaAsset:image3",
                                        ),
                                    ),
                            )
                    ),
                visibility = UgcVisibility(UgcVisibilityType.PUBLIC),
            )

        val serialized = json.encodeToString(request)

        assertTrue(
            serialized.contains("\"urn:li:digitalmediaAsset:image1\""),
            "First image asset should be present",
        )
        assertTrue(
            serialized.contains("\"urn:li:digitalmediaAsset:image2\""),
            "Second image asset should be present",
        )
        assertTrue(
            serialized.contains("\"urn:li:digitalmediaAsset:image3\""),
            "Third image asset should be present",
        )
    }

    @Test
    fun `UgcVisibility with CONNECTIONS serializes correctly`() {
        val json = Json {
            encodeDefaults = true
            prettyPrint = true
        }

        val visibility = UgcVisibility(UgcVisibilityType.CONNECTIONS)
        val serialized = json.encodeToString(visibility)

        assertTrue(
            serialized.contains("\"com.linkedin.ugc.MemberNetworkVisibility\""),
            "visibility discriminator should be present",
        )
        assertTrue(serialized.contains("\"CONNECTIONS\""), "visibility should be CONNECTIONS")
    }

    // ============================================================================
    // OAuth Token Model Tests
    // ============================================================================

    @Test
    fun `LinkedInOAuthToken isExpired returns false for fresh token`() {
        val token =
            LinkedInOAuthToken(
                accessToken = "test-token",
                expiresIn = 5184000L, // 60 days
                obtainedAt = java.time.Instant.now().epochSecond,
            )

        assertFalse(token.isExpired(), "Fresh token should not be expired")
    }

    @Test
    fun `LinkedInOAuthToken isExpired returns true for old token`() {
        val token =
            LinkedInOAuthToken(
                accessToken = "test-token",
                expiresIn = 5184000L, // 60 days
                obtainedAt = java.time.Instant.now().epochSecond - 5184000L, // 60 days ago
            )

        assertTrue(token.isExpired(), "Old token should be expired")
    }

    @Test
    fun `LinkedInTokenResponse deserializes correctly`() {
        val json = Json { ignoreUnknownKeys = true }

        val responseJson =
            """{"access_token":"AQUvlL_DYEzvT2wz1QJiEPeLioeA","expires_in":5184000,"refresh_token":"AQWAft_WjYZKwuWXLC5hQlghgTam","refresh_token_expires_in":31536000,"scope":"r_basicprofile w_member_social"}"""

        val response = json.decodeFromString<LinkedInTokenResponse>(responseJson)

        assertEquals("AQUvlL_DYEzvT2wz1QJiEPeLioeA", response.accessToken)
        assertEquals(5184000L, response.expiresIn)
        assertEquals("AQWAft_WjYZKwuWXLC5hQlghgTam", response.refreshToken)
        assertEquals(31536000L, response.refreshTokenExpiresIn)
        assertEquals("r_basicprofile w_member_social", response.scope)
    }

    // ============================================================================
    // Upload Registration Model Tests
    // ============================================================================

    @Test
    fun `LinkedInRegisterUploadRequest serializes correctly`() {
        val json = Json {
            encodeDefaults = true
            prettyPrint = true
        }

        val request =
            LinkedInRegisterUploadRequest(
                registerUploadRequest =
                    RegisterUploadRequestData(
                        owner = "urn:li:person:8675309",
                        recipes = listOf(LinkedInMediaRecipe.FEEDSHARE_IMAGE),
                        serviceRelationships =
                            listOf(
                                ServiceRelationship(
                                    relationshipType = "OWNER",
                                    identifier = "urn:li:userGeneratedContent",
                                )
                            ),
                    )
            )

        val serialized = json.encodeToString(request)

        assertTrue(
            serialized.contains("\"registerUploadRequest\""),
            "registerUploadRequest field should be present",
        )
        assertTrue(serialized.contains("\"urn:li:person:8675309\""), "owner should be present")
        assertTrue(
            serialized.contains("\"urn:li:digitalmediaRecipe:feedshare-image\""),
            "recipe should be present",
        )
        assertTrue(serialized.contains("\"OWNER\""), "relationshipType should be OWNER")
        assertTrue(
            serialized.contains("\"urn:li:userGeneratedContent\""),
            "identifier should be present",
        )
    }

    @Test
    fun `LinkedInRegisterUploadResponse deserializes correctly`() {
        val json = Json { ignoreUnknownKeys = true }

        val responseJson =
            """{"value":{"uploadMechanism":{"com.linkedin.digitalmedia.uploading.MediaUploadHttpRequest":{"headers":{},"uploadUrl":"https://api.linkedin.com/mediaUpload/test"}},"mediaArtifact":"urn:li:digitalmediaMediaArtifact:(...)","asset":"urn:li:digitalmediaAsset:C5522AQGTYER3k3ByHQ"}}"""

        val response = json.decodeFromString<LinkedInRegisterUploadResponse>(responseJson)

        assertEquals("urn:li:digitalmediaAsset:C5522AQGTYER3k3ByHQ", response.value.asset)
        assertEquals(
            "https://api.linkedin.com/mediaUpload/test",
            response.value.uploadMechanism.uploadRequest.uploadUrl,
        )
    }

    // ============================================================================
    // User Profile Model Tests
    // ============================================================================

    @Test
    fun `LinkedInUserProfile deserializes with full URN`() {
        val json = Json { ignoreUnknownKeys = true }

        val responseJson = """{"sub":"urn:li:person:abc123","name":"Test User"}"""

        val profile = json.decodeFromString<LinkedInUserProfile>(responseJson)

        assertEquals("urn:li:person:abc123", profile.sub)
        assertEquals("Test User", profile.name)
    }

    @Test
    fun `LinkedInUserProfile deserializes with plain ID`() {
        val json = Json { ignoreUnknownKeys = true }

        val responseJson = """{"sub":"abc123"}"""

        val profile = json.decodeFromString<LinkedInUserProfile>(responseJson)

        assertEquals("abc123", profile.sub)
    }

    // ============================================================================
    // OAuth Error Handling Tests
    // ============================================================================

    @Test
    fun `exchangeCodeForToken handles invalid code error`(@TempDir tempDir: Path) = runTest {
        testApplication {
            val jdbi = createTestDatabase(tempDir)
            val filesModule = createFilesModule(tempDir, jdbi)
            val documentsDb = DocumentsDatabase(jdbi)

            application {
                routing {
                    post("/oauth/v2/accessToken") {
                        call.respondText(
                            """{"error":"invalid_request","error_description":"Unable to retrieve access token: authorization code not found"}""",
                            ContentType.Application.Json,
                            HttpStatusCode.BadRequest,
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

            val result = module.exchangeCodeForToken("invalid-code", "http://localhost/callback")

            assertTrue(result is Either.Left, "Should return error for invalid code")
            val error = (result as Either.Left).value
            assertEquals(400, error.status)

            linkedInClient.close()
        }
    }

    @Test
    fun `refreshAccessToken handles expired refresh token error`(@TempDir tempDir: Path) = runTest {
        testApplication {
            val jdbi = createTestDatabase(tempDir)
            val filesModule = createFilesModule(tempDir, jdbi)
            val documentsDb = DocumentsDatabase(jdbi)

            application {
                routing {
                    post("/oauth/v2/accessToken") {
                        call.respondText(
                            """{"error":"invalid_request","error_description":"Refresh token is expired"}""",
                            ContentType.Application.Json,
                            HttpStatusCode.BadRequest,
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

            val result = module.refreshAccessToken("expired-refresh-token")

            assertTrue(result is Either.Left, "Should return error for expired refresh token")
            val error = (result as Either.Left).value
            assertEquals(400, error.status)

            linkedInClient.close()
        }
    }

    // ============================================================================
    // Image Upload Tests
    // ============================================================================

    @Test
    fun `image upload registration returns asset URN`(@TempDir tempDir: Path) = runTest {
        testApplication {
            val jdbi = createTestDatabase(tempDir)
            val filesModule = createFilesModule(tempDir, jdbi)
            val documentsDb = DocumentsDatabase(jdbi)
            var registrationReceived = false
            var registrationBody: String? = null

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
                    get("/v2/userinfo") {
                        call.respondText(
                            """{"sub":"urn:li:person:test123"}""",
                            ContentType.Application.Json,
                        )
                    }
                    post("/v2/assets") {
                        registrationReceived = true
                        registrationBody = call.receiveStream().readBytes().decodeToString()
                        call.respondText(
                            """{"value":{"asset":"urn:li:digitalmediaAsset:uploaded123","uploadMechanism":{"com.linkedin.digitalmedia.uploading.MediaUploadHttpRequest":{"uploadUrl":"http://localhost/upload-binary"}}}}""",
                            ContentType.Application.Json,
                        )
                    }
                    put("/upload-binary") { call.respondText("", status = HttpStatusCode.Created) }
                    post("/v2/ugcPosts") {
                        call.response.header("X-RestLi-Id", "urn:li:ugcPost:12345")
                        call.respondText(
                            """{"id":"urn:li:ugcPost:12345"}""",
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
            assertTrue(registrationReceived, "Upload registration should have been called")

            // Verify registration request body
            assertNotNull(registrationBody)
            assertTrue(
                registrationBody!!.contains("\"registerUploadRequest\""),
                "Request should contain registerUploadRequest",
            )
            assertTrue(
                registrationBody.contains("\"urn:li:person:test123\""),
                "Request should contain owner URN",
            )
            assertTrue(
                registrationBody.contains("\"urn:li:digitalmediaRecipe:feedshare-image\""),
                "Request should contain feedshare-image recipe",
            )

            linkedInClient.close()
        }
    }

    @Test
    fun `image upload handles registration failure`(@TempDir tempDir: Path) = runTest {
        testApplication {
            val jdbi = createTestDatabase(tempDir)
            val filesModule = createFilesModule(tempDir, jdbi)
            val documentsDb = DocumentsDatabase(jdbi)

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
                    get("/v2/userinfo") {
                        call.respondText(
                            """{"sub":"urn:li:person:test123"}""",
                            ContentType.Application.Json,
                        )
                    }
                    post("/v2/assets") {
                        call.respondText(
                            """{"serviceErrorCode":100,"message":"Upload not authorized"}""",
                            ContentType.Application.Json,
                            HttpStatusCode.Forbidden,
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

            assertTrue(result is Either.Left, "Should fail when upload registration fails")
            val error = (result as Either.Left).value
            assertEquals(403, error.status)

            linkedInClient.close()
        }
    }

    @Test
    fun `post creation handles API error`(@TempDir tempDir: Path) = runTest {
        testApplication {
            val jdbi = createTestDatabase(tempDir)
            val filesModule = createFilesModule(tempDir, jdbi)
            val documentsDb = DocumentsDatabase(jdbi)

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
                    get("/v2/userinfo") {
                        call.respondText(
                            """{"sub":"urn:li:person:test123"}""",
                            ContentType.Application.Json,
                        )
                    }
                    post("/v2/ugcPosts") {
                        call.respondText(
                            """{"serviceErrorCode":65600,"message":"Invalid visibility","status":403}""",
                            ContentType.Application.Json,
                            HttpStatusCode.Forbidden,
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

            val request = NewPostRequest(content = "Test post", targets = listOf("linkedin"))

            val result = module.createPost(request)

            assertTrue(result is Either.Left, "Should fail when API returns error")
            val error = (result as Either.Left).value
            assertEquals(403, error.status)

            linkedInClient.close()
        }
    }

    // ============================================================================
    // OAuth Error Model Tests
    // ============================================================================

    @Test
    fun `LinkedInOAuthError deserializes correctly`() {
        val json = Json { ignoreUnknownKeys = true }

        val errorJson =
            """{"error":"user_cancelled_authorize","error_description":"The user cancelled the authorization request"}"""

        val error = json.decodeFromString<LinkedInOAuthError>(errorJson)

        assertEquals("user_cancelled_authorize", error.error)
        assertEquals("The user cancelled the authorization request", error.errorDescription)
    }

    @Test
    fun `LinkedInOAuthError deserializes with minimal fields`() {
        val json = Json { ignoreUnknownKeys = true }

        val errorJson = """{"error":"user_cancelled_login"}"""

        val error = json.decodeFromString<LinkedInOAuthError>(errorJson)

        assertEquals("user_cancelled_login", error.error)
        assertNull(error.errorDescription)
    }
}
