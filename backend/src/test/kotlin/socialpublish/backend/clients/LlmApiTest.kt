package socialpublish.backend.clients

import arrow.core.Either
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import java.nio.file.Path
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import socialpublish.backend.clients.llm.LlmApiModule
import socialpublish.backend.clients.llm.LlmConfig
import socialpublish.backend.clients.llm.OpenAiChatRequest
import socialpublish.backend.db.UUIDv7
import socialpublish.backend.server.routes.FilesRoutes
import socialpublish.backend.testutils.createFilesModule
import socialpublish.backend.testutils.createTestDatabase
import socialpublish.backend.testutils.createTestSession
import socialpublish.backend.testutils.uploadTestImage

private data class CapturedLlmRequest(
    val url: String,
    val session: String?,
    val userAgent: String?,
    val authorization: String?,
)

class LlmApiTest {
    private val testUserUuid: UUIDv7 =
        UUIDv7.fromString("00000000-0000-0000-0000-000000000001")

    @Test
    fun `generates alt text through OpenCode Go`(@TempDir tempDir: Path) =
        runTest {
            testApplication {
                val jdbi = createTestDatabase(tempDir)
                val filesModule = createFilesModule(tempDir, jdbi)
                val filesRoutes = FilesRoutes(filesModule)
                val receivedRequests = mutableListOf<CapturedLlmRequest>()

                application {
                    install(ContentNegotiation) {
                        json(
                            Json {
                                ignoreUnknownKeys = true
                                isLenient = true
                            }
                        )
                    }
                    routing {
                        post("/api/files/upload") {
                            context(createTestSession(testUserUuid)) {
                                filesRoutes.uploadFileRoute(call)
                            }
                        }
                    }
                }

                val uploadClient = createClient {
                    install(ClientContentNegotiation) {
                        json(
                            Json {
                                ignoreUnknownKeys = true
                                isLenient = true
                            }
                        )
                    }
                }

                val mockEngine = MockEngine { request ->
                    receivedRequests.add(
                        CapturedLlmRequest(
                            url = request.url.toString(),
                            session = request.headers["x-opencode-session"],
                            userAgent = request.headers[HttpHeaders.UserAgent],
                            authorization =
                                request.headers[HttpHeaders.Authorization],
                        )
                    )
                    respond(
                        content =
                            ByteReadChannel(
                                """
                                {
                                    "choices": [
                                        {
                                            "message": {
                                                "content": "A mocked alt text response"
                                            }
                                        }
                                    ]
                                }
                                """
                                    .trimIndent()
                            ),
                        status = HttpStatusCode.OK,
                        headers =
                            headersOf(
                                HttpHeaders.ContentType,
                                ContentType.Application.Json.toString(),
                            ),
                    )
                }
                val llmClient =
                    HttpClient(mockEngine) {
                        install(ClientContentNegotiation) {
                            json(
                                Json {
                                    ignoreUnknownKeys = true
                                    isLenient = true
                                }
                            )
                        }
                    }

                try {
                    val goApiUrl =
                        "https://opencode.ai/zen/go/v1/chat/completions"
                    val llmModule = LlmApiModule(filesModule, llmClient)

                    suspend fun generateAltText(
                        apiUrl: String,
                        imageUuid: String,
                    ) =
                        context(createTestSession(testUserUuid)) {
                            llmModule.generateAltText(
                                LlmConfig(
                                    apiUrl = apiUrl,
                                    apiKey = "test-key",
                                    model = "gpt-4o-mini",
                                ),
                                imageUuid,
                            )
                        }

                    val flower1 =
                        uploadTestImage(uploadClient, "flower1.jpeg", "")
                    val flower2 =
                        uploadTestImage(uploadClient, "flower2.jpeg", "")

                    val flower1Results =
                        listOf(
                            generateAltText(goApiUrl, flower1.uuid),
                            generateAltText(goApiUrl, flower1.uuid),
                        )
                    val flower2Result = generateAltText(goApiUrl, flower2.uuid)

                    flower1Results.forEach { result ->
                        assertTrue(
                            result is Either.Right,
                            "Expected successful result",
                        )
                        assertEquals(
                            "A mocked alt text response",
                            (result as Either.Right).value,
                        )
                    }
                    assertTrue(
                        flower2Result is Either.Right,
                        "Expected successful result",
                    )
                    assertEquals(
                        "A mocked alt text response",
                        (flower2Result as Either.Right).value,
                    )

                    val expectedFlower1Session =
                        "social-publish-alt-text-${flower1.uuid}"
                    val expectedFlower2Session =
                        "social-publish-alt-text-${flower2.uuid}"
                    assertEquals(3, receivedRequests.size)
                    assertEquals(
                        listOf(goApiUrl, goApiUrl, goApiUrl),
                        receivedRequests.map { it.url },
                    )
                    assertEquals(
                        expectedFlower1Session,
                        receivedRequests[0].session,
                    )
                    assertEquals(
                        expectedFlower1Session,
                        receivedRequests[1].session,
                    )
                    assertNotEquals(
                        receivedRequests[0].session,
                        receivedRequests[2].session,
                    )
                    assertEquals(
                        expectedFlower2Session,
                        receivedRequests[2].session,
                    )
                    receivedRequests.forEach { request ->
                        assertEquals("social-publish", request.userAgent)
                        assertEquals("Bearer test-key", request.authorization)
                    }

                    val nonGoApiUrls =
                        listOf(
                            "https://api.openai.com/v1/chat/completions",
                            "https://opencode.ai.example.com/zen/go/v1/chat/completions",
                            "http://opencode.ai/zen/go/v1/chat/completions",
                            "https://opencode.ai/zen/v1/chat/completions",
                        )
                    nonGoApiUrls.forEach { apiUrl ->
                        val result = generateAltText(apiUrl, flower1.uuid)
                        assertTrue(
                            result is Either.Right,
                            "Expected successful result",
                        )
                        assertEquals(
                            "A mocked alt text response",
                            (result as Either.Right).value,
                        )
                    }

                    assertEquals(
                        nonGoApiUrls,
                        receivedRequests.drop(3).map { it.url },
                    )
                    receivedRequests.drop(3).forEach { request ->
                        assertNull(request.session)
                        assertNull(request.userAgent)
                        assertEquals("Bearer test-key", request.authorization)
                    }
                } finally {
                    llmClient.close()
                    mockEngine.close()
                }
            }
        }

    @Test
    fun `generates alt text using OpenAI`(@TempDir tempDir: Path) = runTest {
        testApplication {
            val jdbi = createTestDatabase(tempDir)
            val filesModule = createFilesModule(tempDir, jdbi)
            val filesRoutes = FilesRoutes(filesModule)
            var receivedRequest: OpenAiChatRequest? = null

            application {
                install(ContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            isLenient = true
                        }
                    )
                }
                routing {
                    post("/api/files/upload") {
                        context(createTestSession(testUserUuid)) {
                            filesRoutes.uploadFileRoute(call)
                        }
                    }
                    // Mock OpenAI API
                    post("/v1/chat/completions") {
                        receivedRequest = call.receive<OpenAiChatRequest>()
                        call.respondText(
                            """
                            {
                                "choices": [
                                    {
                                        "message": {
                                            "content": "A beautiful red rose in bloom"
                                        }
                                    }
                                ]
                            }
                            """
                                .trimIndent(),
                            io.ktor.http.ContentType.Application.Json,
                        )
                    }
                }
            }

            val client = createClient {
                install(ClientContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            isLenient = true
                        }
                    )
                }
            }

            // Upload a test image
            val upload = uploadTestImage(client, "flower1.jpeg", "")

            // Create LLM module with config pointing to mock server
            val llmModule = LlmApiModule(filesModule, client)

            // Generate alt-text
            val llmConfig =
                LlmConfig(
                    apiUrl = "/v1/chat/completions",
                    apiKey = "test-key",
                    model = "gpt-4o-mini",
                )
            val result =
                context(createTestSession(testUserUuid)) {
                    llmModule.generateAltText(llmConfig, upload.uuid)
                }

            // Verify result
            assertTrue(
                result is Either.Right,
                "Expected successful result but got: $result",
            )
            val altText = (result as Either.Right).value
            assertEquals("A beautiful red rose in bloom", altText)

            // Verify request was made correctly
            assertNotNull(receivedRequest)
            assertEquals("gpt-4o-mini", receivedRequest?.model)
            assertEquals(1, receivedRequest?.messages?.size)
            val message = receivedRequest?.messages?.first()
            assertNotNull(message)
            assertEquals("user", message?.role)
            assertEquals(2, message?.content?.size) // text + image
            assertTrue(
                message?.content?.any { it.type == "text" } == true,
                "Should have text content",
            )
            assertTrue(
                message?.content?.any { it.type == "image_url" } == true,
                "Should have image content",
            )
            val imageContent = message?.content?.find { it.type == "image_url" }
            assertNotNull(imageContent?.imageUrl)
            assertTrue(
                imageContent
                    ?.imageUrl
                    ?.url
                    ?.startsWith("data:image/jpeg;base64,") == true,
                "Image should be base64 encoded",
            )
        }
    }

    @Test
    fun `generates alt text using Mistral`(@TempDir tempDir: Path) = runTest {
        testApplication {
            val jdbi = createTestDatabase(tempDir)
            val filesModule = createFilesModule(tempDir, jdbi)
            val filesRoutes = FilesRoutes(filesModule)

            application {
                install(ContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            isLenient = true
                        }
                    )
                }
                routing {
                    post("/api/files/upload") {
                        context(createTestSession(testUserUuid)) {
                            filesRoutes.uploadFileRoute(call)
                        }
                    }
                    // Mock Mistral API
                    post("/v1/chat/completions") {
                        call.respondText(
                            """
                            {
                                "choices": [
                                    {
                                        "message": {
                                            "content": "A vibrant yellow tulip against a green background"
                                        }
                                    }
                                ]
                            }
                            """
                                .trimIndent(),
                            io.ktor.http.ContentType.Application.Json,
                        )
                    }
                }
            }

            val client = createClient {
                install(ClientContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            isLenient = true
                        }
                    )
                }
            }

            // Upload a test image
            val upload = uploadTestImage(client, "flower2.jpeg", "")

            // Create LLM module with Mistral config pointing to mock server
            val llmModule = LlmApiModule(filesModule, client)

            // Generate alt-text
            val llmConfig =
                LlmConfig(
                    apiUrl = "/v1/chat/completions",
                    apiKey = "test-key",
                    model = "pixtral-12b-2409",
                )
            val result =
                context(createTestSession(testUserUuid)) {
                    llmModule.generateAltText(llmConfig, upload.uuid)
                }

            // Verify result
            assertTrue(result is Either.Right, "Expected successful result")
            val altText = (result as Either.Right).value
            assertEquals(
                "A vibrant yellow tulip against a green background",
                altText,
            )
        }
    }

    @Test
    fun `returns error for non-existent image`(@TempDir tempDir: Path) =
        runTest {
            testApplication {
                val jdbi = createTestDatabase(tempDir)
                val filesModule = createFilesModule(tempDir, jdbi)

                val client = createClient {
                    install(ClientContentNegotiation) {
                        json(
                            Json {
                                ignoreUnknownKeys = true
                                isLenient = true
                            }
                        )
                    }
                }

                val llmModule = LlmApiModule(filesModule, client)

                val dummyConfig =
                    LlmConfig(
                        apiUrl = "/v1/chat/completions",
                        apiKey = "test-key",
                        model = "gpt-4o-mini",
                    )
                val result =
                    context(createTestSession(testUserUuid)) {
                        llmModule.generateAltText(
                            dummyConfig,
                            "non-existent-uuid",
                        )
                    }

                assertTrue(result is Either.Left, "Expected error result")
                val error = (result as Either.Left).value
                assertEquals(404, error.status)
                assertTrue(
                    error.errorMessage.contains("not found", ignoreCase = true)
                )
            }
        }
}
