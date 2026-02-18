package socialpublish.backend.clients

import arrow.core.Either
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import socialpublish.backend.clients.llm.LlmApiModule
import socialpublish.backend.clients.llm.LlmConfig
import socialpublish.backend.clients.llm.OpenAiChatRequest
import socialpublish.backend.server.routes.FilesRoutes
import socialpublish.backend.testutils.createFilesModule
import socialpublish.backend.testutils.createTestDatabase
import socialpublish.backend.testutils.uploadTestImage

class LlmApiTest {
    private val testUserUuid: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")

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
                    post("/api/files/upload") { filesRoutes.uploadFileRoute(testUserUuid, call) }
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
            val result = llmModule.generateAltText(llmConfig, upload.uuid)

            // Verify result
            assertTrue(result is Either.Right, "Expected successful result but got: $result")
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
                imageContent?.imageUrl?.url?.startsWith("data:image/jpeg;base64,") == true,
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
                    post("/api/files/upload") { filesRoutes.uploadFileRoute(testUserUuid, call) }
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
            val result = llmModule.generateAltText(llmConfig, upload.uuid)

            // Verify result
            assertTrue(result is Either.Right, "Expected successful result")
            val altText = (result as Either.Right).value
            assertEquals("A vibrant yellow tulip against a green background", altText)
        }
    }

    @Test
    fun `returns error for non-existent image`(@TempDir tempDir: Path) = runTest {
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
            val result = llmModule.generateAltText(dummyConfig, "non-existent-uuid")

            assertTrue(result is Either.Left, "Expected error result")
            val error = (result as Either.Left).value
            assertEquals(404, error.status)
            assertTrue(error.errorMessage.contains("not found", ignoreCase = true))
        }
    }
}
