package socialpublish.backend.clients.llm

import arrow.core.left
import arrow.core.right
import arrow.fx.coroutines.Resource
import arrow.fx.coroutines.resource
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import java.util.Base64
import kotlinx.serialization.json.Json
import socialpublish.backend.models.ApiResult
import socialpublish.backend.models.CaughtException
import socialpublish.backend.models.RequestError
import socialpublish.backend.models.ResponseBody
import socialpublish.backend.models.ValidationError
import socialpublish.backend.modules.FilesModule

private val logger = KotlinLogging.logger {}

class LlmApiModule(
    private val config: LlmConfig,
    private val filesModule: FilesModule,
    private val httpClient: HttpClient,
    private val openAiApiUrl: String = "https://api.openai.com/v1/chat/completions",
    private val mistralApiUrl: String = "https://api.mistral.ai/v1/chat/completions",
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

    suspend fun generateAltText(imageUuid: String): ApiResult<String> {
        return try {
            // Read the image file
            val file =
                filesModule.readImageFile(imageUuid)
                    ?: return ValidationError(
                            status = 404,
                            errorMessage = "Image not found — uuid: $imageUuid",
                            module = "llm",
                        )
                        .left()

            // Convert image to base64 data URL
            val base64Image = Base64.getEncoder().encodeToString(file.bytes)
            val dataUrl = "data:${file.mimetype};base64,$base64Image"

            // Call the appropriate LLM API based on provider
            when (config.provider.lowercase()) {
                "openai" -> generateAltTextOpenAi(dataUrl)
                "mistral" -> generateAltTextMistral(dataUrl)
                else ->
                    ValidationError(
                            status = 400,
                            errorMessage = "Unsupported LLM provider: ${config.provider}",
                            module = "llm",
                        )
                        .left()
            }
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

    private suspend fun generateAltTextOpenAi(dataUrl: String): ApiResult<String> {
        return try {
            val model = config.model ?: "gpt-4o-mini"
            val request =
                OpenAiChatRequest(
                    model = model,
                    messages =
                        listOf(
                            OpenAiMessage(
                                role = "user",
                                content =
                                    listOf(
                                        OpenAiContent(
                                            type = "text",
                                            text =
                                                "Please provide a concise and descriptive alt text for this image. " +
                                                    "The alt text should be suitable for accessibility purposes and describe the key visual elements. " +
                                                    "Keep it under 100 words and focus on what's important in the image.",
                                        ),
                                        OpenAiContent(
                                            type = "image_url",
                                            imageUrl = OpenAiImageUrl(url = dataUrl),
                                        ),
                                    ),
                            )
                        ),
                    maxTokens = 300,
                )

            logger.info { "Calling OpenAI API to generate alt-text (model: $model)" }

            val response =
                httpClient.post(openAiApiUrl) {
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
                                    errorMessage = "No alt-text generated by OpenAI",
                                    module = "llm",
                                )
                                .left()

                    logger.info { "Generated alt-text: $altText" }
                    altText.right()
                }
                else -> {
                    val errorBody = response.bodyAsText()
                    logger.warn { "OpenAI API error: ${response.status}, body: $errorBody" }
                    RequestError(
                            status = response.status.value,
                            module = "llm",
                            errorMessage = "OpenAI API request failed",
                            body = ResponseBody(asString = errorBody),
                        )
                        .left()
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to call OpenAI API" }
            CaughtException(
                    status = 500,
                    module = "llm",
                    errorMessage = "OpenAI API call failed: ${e.message}",
                )
                .left()
        }
    }

    private suspend fun generateAltTextMistral(dataUrl: String): ApiResult<String> {
        return try {
            val model = config.model ?: "pixtral-12b-2409"
            val request =
                MistralChatRequest(
                    model = model,
                    messages =
                        listOf(
                            MistralMessage(
                                role = "user",
                                content =
                                    listOf(
                                        MistralContent(
                                            type = "text",
                                            text =
                                                "Please provide a concise and descriptive alt text for this image. " +
                                                    "The alt text should be suitable for accessibility purposes and describe the key visual elements. " +
                                                    "Keep it under 100 words and focus on what's important in the image.",
                                        ),
                                        MistralContent(type = "image_url", imageUrl = dataUrl),
                                    ),
                            )
                        ),
                    maxTokens = 300,
                )

            logger.info { "Calling Mistral API to generate alt-text (model: $model)" }

            val response =
                httpClient.post(mistralApiUrl) {
                    header("Authorization", "Bearer ${config.apiKey}")
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }

            when (response.status.value) {
                200 -> {
                    val data = response.body<MistralChatResponse>()
                    val altText =
                        data.choices.firstOrNull()?.message?.content?.trim()
                            ?: return ValidationError(
                                    status = 500,
                                    errorMessage = "No alt-text generated by Mistral",
                                    module = "llm",
                                )
                                .left()

                    logger.info { "Generated alt-text: $altText" }
                    altText.right()
                }
                else -> {
                    val errorBody = response.bodyAsText()
                    logger.warn { "Mistral API error: ${response.status}, body: $errorBody" }
                    RequestError(
                            status = response.status.value,
                            module = "llm",
                            errorMessage = "Mistral API request failed",
                            body = ResponseBody(asString = errorBody),
                        )
                        .left()
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to call Mistral API" }
            CaughtException(
                    status = 500,
                    module = "llm",
                    errorMessage = "Mistral API call failed: ${e.message}",
                )
                .left()
        }
    }
}
