package socialpublish.backend.integrations.linkedin

import arrow.core.Either
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.request.receiveStream
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
                url.contains("scope=openid+profile+w_member_social"),
                "URL should contain openid profile w_member_social scope",
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
                    post("/v2/posts") {
                        postCreated = true
                        postBody = call.receiveStream().readBytes().decodeToString()
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

            // Verify the post body contains all required fields
            assertNotNull(postBody)
            assertTrue(
                postBody!!.contains("\"distribution\""),
                "Post body should contain distribution field",
            )
            assertTrue(
                postBody.contains("\"feedDistribution\""),
                "Post body should contain feedDistribution field",
            )
            assertTrue(
                postBody.contains("\"MAIN_FEED\""),
                "Post body should contain MAIN_FEED value",
            )
            assertTrue(
                postBody.contains("\"lifecycleState\""),
                "Post body should contain lifecycleState field",
            )
            assertTrue(
                postBody.contains("\"PUBLISHED\""),
                "Post body should contain PUBLISHED value",
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
                    post("/v2/posts") {
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
                    post("/v2/posts") {
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
                        post("/v2/posts") {
                            postCreated = true
                            postBody = call.receiveStream().readBytes().decodeToString()
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
                    NewPostRequest(
                        content = "Check out this link!",
                        targets = listOf("linkedin"),
                        link = "http://localhost/preview-page",
                    )

                val result = module.createPost(request)

                assertTrue(result is Either.Right)
                assertTrue(thumbnailDownloaded, "Thumbnail image should have been downloaded")
                assertTrue(thumbnailUploaded, "Thumbnail should have been uploaded to LinkedIn")
                assertTrue(postCreated, "Post should have been created")

                // Verify the post body contains the thumbnail URN, not the public URL
                assertNotNull(postBody)
                assertTrue(
                    postBody!!.contains("urn:li:digitalmediaAsset:thumbnail123"),
                    "Post should contain LinkedIn asset URN for thumbnail",
                )
                assertFalse(
                    postBody.contains("http://localhost/preview-image.jpg"),
                    "Post should NOT contain public image URL",
                )

                linkedInClient.close()
            }
        }

    // TODO: Add automated test for LinkedIn OAuth callback error handling
    // The implementation correctly handles LinkedIn OAuth errors by redirecting to
    // /account?error=...
    // Manual testing confirmed this works correctly with the user-friendly error message.

    @Test
    fun `Distribution serializes with all required fields`() {
        val json = Json {
            encodeDefaults = true
            prettyPrint = true
        }

        val distribution = Distribution()
        val serialized = json.encodeToString(distribution)

        // Verify all required fields are present
        assertTrue(
            serialized.contains("\"feedDistribution\""),
            "feedDistribution field should be present",
        )
        assertTrue(
            serialized.contains("\"MAIN_FEED\""),
            "feedDistribution value should be MAIN_FEED",
        )
        assertTrue(
            serialized.contains("\"targetEntities\""),
            "targetEntities field should be present",
        )
        assertTrue(
            serialized.contains("\"thirdPartyDistributionChannels\""),
            "thirdPartyDistributionChannels field should be present",
        )
    }

    @Test
    fun `LinkedInPostRequest serializes with all required fields including defaults`() {
        val json = Json {
            encodeDefaults = true
            prettyPrint = true
        }

        val request =
            LinkedInPostRequest(
                author = "urn:li:person:test123",
                commentary = "Test post content",
                visibility = "PUBLIC",
                distribution = Distribution(),
                lifecycleState = "PUBLISHED",
            )

        val serialized = json.encodeToString(request)

        // Verify all required fields are present
        assertTrue(serialized.contains("\"author\""), "author field should be present")
        assertTrue(
            serialized.contains("\"urn:li:person:test123\""),
            "author value should be present",
        )
        assertTrue(serialized.contains("\"commentary\""), "commentary field should be present")
        assertTrue(
            serialized.contains("\"Test post content\""),
            "commentary value should be present",
        )
        assertTrue(serialized.contains("\"visibility\""), "visibility field should be present")
        assertTrue(serialized.contains("\"PUBLIC\""), "visibility value should be PUBLIC")
        assertTrue(serialized.contains("\"distribution\""), "distribution field should be present")
        assertTrue(
            serialized.contains("\"feedDistribution\""),
            "feedDistribution field should be present in distribution",
        )
        assertTrue(
            serialized.contains("\"MAIN_FEED\""),
            "feedDistribution value should be MAIN_FEED",
        )
        assertTrue(
            serialized.contains("\"lifecycleState\""),
            "lifecycleState field should be present",
        )
        assertTrue(serialized.contains("\"PUBLISHED\""), "lifecycleState value should be PUBLISHED")
    }

    @Test
    fun `LinkedInPostRequest with content serializes correctly`() {
        val json = Json {
            encodeDefaults = true
            prettyPrint = true
        }

        val request =
            LinkedInPostRequest(
                author = "urn:li:person:test123",
                commentary = "Test post with image",
                visibility = "PUBLIC",
                distribution = Distribution(),
                content =
                    PostContent(media = MediaContent(id = "urn:li:digitalmediaAsset:image123")),
                lifecycleState = "PUBLISHED",
            )

        val serialized = json.encodeToString(request)

        // Verify content structure
        assertTrue(serialized.contains("\"content\""), "content field should be present")
        assertTrue(serialized.contains("\"media\""), "media field should be present in content")
        assertTrue(serialized.contains("\"id\""), "id field should be present in media")
        assertTrue(
            serialized.contains("\"urn:li:digitalmediaAsset:image123\""),
            "media id value should be present",
        )
    }

    @Test
    fun `Distribution with custom values serializes correctly`() {
        val json = Json {
            encodeDefaults = true
            prettyPrint = true
        }

        val distribution =
            Distribution(
                feedDistribution = "NONE",
                targetEntities = listOf("urn:li:organization:123"),
                thirdPartyDistributionChannels = emptyList(),
            )

        val serialized = json.encodeToString(distribution)

        assertTrue(
            serialized.contains("\"feedDistribution\""),
            "feedDistribution field should be present",
        )
        assertTrue(
            serialized.contains("\"NONE\""),
            "feedDistribution custom value should be present",
        )
        assertTrue(
            serialized.contains("\"targetEntities\""),
            "targetEntities field should be present",
        )
        assertTrue(
            serialized.contains("\"urn:li:organization:123\""),
            "targetEntities value should be present",
        )
    }
}
