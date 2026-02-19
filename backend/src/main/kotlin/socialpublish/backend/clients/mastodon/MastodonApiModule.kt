package socialpublish.backend.clients.mastodon

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import arrow.fx.coroutines.Resource
import arrow.fx.coroutines.resource
import arrow.fx.coroutines.resourceScope
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import socialpublish.backend.common.ApiResult
import socialpublish.backend.common.CaughtException
import socialpublish.backend.common.ErrorResponse
import socialpublish.backend.common.NewMastodonPostResponse
import socialpublish.backend.common.NewPostRequest
import socialpublish.backend.common.NewPostResponse
import socialpublish.backend.common.RequestError
import socialpublish.backend.common.ResponseBody
import socialpublish.backend.common.ValidationError
import socialpublish.backend.modules.FilesModule

private val logger = KotlinLogging.logger {}

class MastodonApiModule(private val filesModule: FilesModule, private val httpClient: HttpClient) {
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

        fun resource(filesModule: FilesModule): Resource<MastodonApiModule> = resource {
            MastodonApiModule(filesModule, defaultHttpClient().bind())
        }
    }

    private fun mediaUrlV2(config: MastodonConfig) = "${config.host}/api/v2/media"

    private fun mediaUrlV1(config: MastodonConfig) = "${config.host}/api/v1/media"

    private fun statusesUrlV1(config: MastodonConfig) = "${config.host}/api/v1/statuses"

    /** Upload media to Mastodon */
    private suspend fun uploadMedia(
        config: MastodonConfig,
        uuid: String,
        userUuid: UUID,
    ): ApiResult<MastodonMediaResponse> = resourceScope {
        try {
            val file =
                filesModule.readImageFile(uuid, userUuid)
                    ?: return@resourceScope ValidationError(
                            status = 404,
                            errorMessage = "Failed to read image file — uuid: $uuid",
                            module = "mastodon",
                        )
                        .left()

            val response =
                httpClient.submitFormWithBinaryData(
                    url = mediaUrlV2(config),
                    formData =
                        formData {
                            append(
                                "file",
                                file.source.asKotlinSource().bind(),
                                Headers.build {
                                    append(HttpHeaders.ContentType, file.mimetype)
                                    append(
                                        HttpHeaders.ContentDisposition,
                                        "filename=\"${file.originalname}\"",
                                    )
                                },
                            )
                            file.altText?.let { append("description", it) }
                        },
                ) {
                    header("Authorization", "Bearer ${config.accessToken}")
                }

            when (response.status.value) {
                200 -> {
                    val data = response.body<MastodonMediaResponse>()
                    data.right()
                }
                202 -> {
                    // Async upload - poll for completion
                    val initialData = response.body<MastodonMediaResponse>()
                    waitForMediaProcessing(config, initialData.id)
                }
                else -> {
                    val errorBody = response.bodyAsText()
                    logger.warn {
                        "Failed to upload media to Mastodon: ${response.status}, body: $errorBody"
                    }
                    RequestError(
                            status = response.status.value,
                            module = "mastodon",
                            errorMessage = "Failed to upload media",
                            body = ResponseBody(asString = errorBody),
                        )
                        .left()
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to upload media (mastodon) — uuid $uuid" }
            CaughtException(
                    status = 500,
                    module = "mastodon",
                    errorMessage = "Failed to upload media — uuid: $uuid",
                )
                .left()
        }
    }

    /** Poll for media processing completion */
    private suspend fun waitForMediaProcessing(
        config: MastodonConfig,
        mediaId: String,
    ): ApiResult<MastodonMediaResponse> {
        (1..30).forEach { _ ->
            // Try for up to 6 seconds
            delay(200)

            val response =
                httpClient.get("${mediaUrlV1(config)}/$mediaId") {
                    header("Authorization", "Bearer ${config.accessToken}")
                }

            when (response.status.value) {
                200 -> {
                    val data = response.body<MastodonMediaResponse>()
                    return data.right()
                }

                202 -> {
                    // Still processing, continue polling
                    return@forEach
                }

                else -> {
                    val errorBody = response.bodyAsText()
                    return RequestError(
                            status = response.status.value,
                            module = "mastodon",
                            errorMessage = "Failed to get media status",
                            body = ResponseBody(asString = errorBody),
                        )
                        .left()
                }
            }
        }

        return CaughtException(
                status = 500,
                module = "mastodon",
                errorMessage = "Media processing timeout",
            )
            .left()
    }

    /** Create a post on Mastodon */
    suspend fun createPost(
        config: MastodonConfig,
        request: NewPostRequest,
        userUuid: UUID,
    ): ApiResult<NewPostResponse> {
        return try {
            // Validate request
            request.validate()?.let { error ->
                return error.left()
            }

            // Upload images if present
            val mediaIds = mutableListOf<String>()
            if (!request.images.isNullOrEmpty()) {
                for (imageUuid in request.images) {
                    when (val result = uploadMedia(config, imageUuid, userUuid)) {
                        is Either.Right -> mediaIds.add(result.value.id)
                        is Either.Left -> return result.value.left()
                    }
                }
            }

            // Prepare status content
            val content =
                if (request.cleanupHtml == true) {
                    cleanupHtml(request.content)
                } else {
                    request.content
                } + if (request.link != null) "\n\n${request.link}" else ""

            logger.info { "Posting to Mastodon:\n${content.trim().prependIndent("  |")}" }

            // Create status
            val response =
                httpClient.submitForm(
                    url = statusesUrlV1(config),
                    formParameters =
                        parameters {
                            append("status", content)
                            if (mediaIds.isNotEmpty()) {
                                mediaIds.forEach { append("media_ids[]", it) }
                            }
                            request.language?.let { append("language", it) }
                        },
                ) {
                    header("Authorization", "Bearer ${config.accessToken}")
                }

            if (response.status.value == 200) {
                val data = response.body<MastodonStatusResponse>()
                NewMastodonPostResponse(uri = data.url).right()
            } else {
                val errorBody = response.bodyAsText()
                logger.warn { "Failed to post to Mastodon: ${response.status}, body: $errorBody" }
                RequestError(
                        status = response.status.value,
                        module = "mastodon",
                        errorMessage = "Failed to create status",
                        body = ResponseBody(asString = errorBody),
                    )
                    .left()
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to post to Mastodon" }
            CaughtException(
                    status = 500,
                    module = "mastodon",
                    errorMessage = "Failed to post to Mastodon: ${e.message}",
                )
                .left()
        }
    }

    /** Handle Mastodon post creation HTTP route */
    suspend fun createPostRoute(call: ApplicationCall, config: MastodonConfig, userUuid: UUID) {
        val request =
            runCatching { call.receive<NewPostRequest>() }.getOrNull()
                ?: run {
                    val params = call.receiveParameters()
                    NewPostRequest(
                        content = params["content"] ?: "",
                        targets = params.getAll("targets"),
                        link = params["link"],
                        language = params["language"],
                        cleanupHtml = params["cleanupHtml"]?.toBoolean(),
                        images = params.getAll("images"),
                    )
                }

        when (val result = createPost(config, request, userUuid)) {
            is Either.Right -> call.respond(result.value)
            is Either.Left -> {
                val error = result.value
                call.respond(
                    HttpStatusCode.fromValue(error.status),
                    ErrorResponse(error = error.errorMessage),
                )
            }
        }
    }

    private fun cleanupHtml(html: String): String {
        return html
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .trim()
    }
}
