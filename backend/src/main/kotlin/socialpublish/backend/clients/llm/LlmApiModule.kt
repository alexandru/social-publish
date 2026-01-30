package socialpublish.backend.clients.llm

import arrow.core.left
import arrow.core.right
import arrow.fx.coroutines.Resource
import arrow.fx.coroutines.resource
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import java.util.Base64
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import socialpublish.backend.common.ApiResult
import socialpublish.backend.common.CaughtException
import socialpublish.backend.common.RequestError
import socialpublish.backend.common.ResponseBody
import socialpublish.backend.common.ValidationError
import socialpublish.backend.modules.FilesModule

private val logger = KotlinLogging.logger {}

class LlmApiModule(
    private val config: LlmConfig,
    private val filesModule: FilesModule,
    private val httpClient: HttpClient,
) {
    companion object {
        fun defaultHttpClient(): Resource<HttpClient> = resource {
            install(
                {
                    HttpClient(CIO) {
                        install(ContentNegotiation) {
                            json(
                                Json {
                                    ignoreUnknownKeys = true
                                    isLenient = true
                                }
                            )
                        }
                        install(HttpTimeout) {
                            requestTimeoutMillis = 20.seconds.inWholeMilliseconds
                            connectTimeoutMillis = 40.seconds.inWholeMilliseconds
                        }
                    }
                },
                { client, _ -> client.close() },
            )
        }

        fun resource(config: LlmConfig, filesModule: FilesModule): Resource<LlmApiModule> =
            resource {
                LlmApiModule(config, filesModule, defaultHttpClient().bind())
            }
    }

    suspend fun generateAltText(
        imageUuid: String,
        userContext: String? = null,
        language: String? = null,
    ): ApiResult<String> {
        return try {
            // Read the already-optimized image file
            // Images are optimized during upload to max 1600x1600, which is sufficient for
            // alt-text generation and prevents API abuse
            val file =
                filesModule.readImageFile(uuid = imageUuid)
                    ?: return ValidationError(
                            status = 404,
                            errorMessage = "Image not found — uuid: $imageUuid",
                            module = "llm",
                        )
                        .left()

            // Convert image to base64 data URL
            val base64Image = Base64.getEncoder().encodeToString(file.bytes)
            val dataUrl = "data:${file.mimetype};base64,$base64Image"

            // Generate alt-text using the LLM API
            generateAltTextFromApi(dataUrl, userContext, language)
        } catch (e: HttpRequestTimeoutException) {
            logger.warn(e) { "LLM request timed out for image $imageUuid" }
            CaughtException(
                    status = 504,
                    module = "llm",
                    errorMessage =
                        "Request timed out. The LLM took too long to respond. Please try again.",
                )
                .left()
        } catch (e: Exception) {
            logger.error(e) { "Failed to generate alt-text for image $imageUuid" }
            CaughtException(
                    status = 500,
                    module = "llm",
                    errorMessage = "Failed to generate alt-text: ${e.message}",
                )
                .left()
        }
    }

    private suspend fun generateAltTextFromApi(
        dataUrl: String,
        extraContextOrInstructions: String?,
        language: String?,
    ): ApiResult<String> {
        return try {
            // Build the prompt text, including user context/instructions if available
            val promptText = run {
                val basePrompt =
                    """
                    Please provide a concise and descriptive alt text for this image.
                    The alt text should be suitable for accessibility purposes. 
                    Keep it under 100 words and focus on what's important in the image.
                    """
                        .trimIndent()

                val languageInstruction =
                    when (language) {
                        "en" -> "\n\nIMPORTANT: Generate the alt-text in English."
                        "ro" -> "\n\nIMPORTANT: Generate the alt-text in Romanian."
                        null -> ""
                        else -> "\n\nIMPORTANT: Generate the alt-text in the language: $language."
                    }

                if (!extraContextOrInstructions.isNullOrBlank()) {
                    "$basePrompt$languageInstruction\n\nFollow user's context/instructions (important!):\n$extraContextOrInstructions"
                } else {
                    "$basePrompt$languageInstruction"
                }
            }

            logger.info { "Prompt for alt-text generation:\n$promptText" }
            val request =
                OpenAiChatRequest(
                    model = config.model,
                    messages =
                        listOf(
                            OpenAiMessage(
                                role = "user",
                                content =
                                    listOf(
                                        OpenAiContent(type = "text", text = promptText),
                                        OpenAiContent(
                                            type = "image_url",
                                            imageUrl = OpenAiImageUrl(url = dataUrl),
                                        ),
                                    ),
                            )
                        ),
                    maxTokens = 300,
                )

            logger.info {
                "Calling LLM API to generate alt-text (model: ${config.model}, url: ${config.apiUrl})"
            }

            val response =
                httpClient.post(config.apiUrl) {
                    header("Authorization", "Bearer ${config.apiKey}")
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }

            when (response.status.value) {
                200 -> {
                    val data = response.body<OpenAiChatResponse>()
                    val altText =
                        data.choices.firstOrNull()?.message?.content?.trim()
                            ?: return ValidationError(
                                    status = 500,
                                    errorMessage = "No alt-text generated by LLM",
                                    module = "llm",
                                )
                                .left()

                    logger.debug { "Generated alt-text (length: ${altText.length})" }
                    altText.right()
                }
                else -> {
                    val errorBody = response.bodyAsText()
                    logger.warn { "LLM API error: ${response.status}, body: $errorBody" }

                    // Remap LLM HTTP errors to 502/503 so the client doesn't treat as
                    // unauthenticated
                    val originalStatus = response.status.value
                    val mappedStatus =
                        when (originalStatus) {
                            401,
                            403,
                            404,
                            429 -> 502 // Bad Gateway: LLM integration problem
                            in 500..599 -> 503 // Service Unavailable: Upstream provider error
                            else -> originalStatus // Validation, not found, etc: propagate
                        }
                    RequestError(
                            status = mappedStatus,
                            module = "llm",
                            errorMessage =
                                "LLM backend error: $originalStatus received from third-party provider",
                            body = ResponseBody(asString = errorBody),
                        )
                        .left()
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to call LLM API" }
            CaughtException(
                    status = 500,
                    module = "llm",
                    errorMessage = "LLM API call failed: ${e.message}",
                )
                .left()
        }
    }
}
