package socialpublish.backend.clients.threads

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import arrow.fx.coroutines.Resource
import arrow.fx.coroutines.resource
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import kotlinx.serialization.json.Json
import socialpublish.backend.common.ApiResult
import socialpublish.backend.common.CaughtException
import socialpublish.backend.common.ErrorResponse
import socialpublish.backend.common.NewPostRequest
import socialpublish.backend.common.NewPostResponse
import socialpublish.backend.common.NewThreadsPostResponse
import socialpublish.backend.common.RequestError
import socialpublish.backend.common.ResponseBody
import socialpublish.backend.modules.FilesModule

private val logger = KotlinLogging.logger {}

class ThreadsApiModule(
    private val config: ThreadsConfig,
    private val filesModule: FilesModule,
    private val httpClient: HttpClient,
) {
    companion object {
        fun defaultHttpClient(): Resource<HttpClient> = resource {
            install({
                HttpClient(CIO) {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }
            }) { client, _ ->
                client.close()
            }
        }

        fun resource(config: ThreadsConfig, filesModule: FilesModule): Resource<ThreadsApiModule> =
            resource {
                ThreadsApiModule(
                    config = config,
                    filesModule = filesModule,
                    httpClient = defaultHttpClient().bind(),
                )
            }
    }

    private suspend fun createMediaContainer(imageUuid: String): ApiResult<String> {
        return try {
            val imageUrl = filesModule.getFileUrl(imageUuid)

            val response =
                httpClient.post("${config.apiBase}/v1.0/${config.userId}/media") {
                    parameter("media_type", "IMAGE")
                    parameter("image_url", imageUrl)
                    parameter("access_token", config.accessToken)
                }

            if (response.status.value == 200) {
                val data = response.body<ThreadsMediaContainerResponse>()
                data.id.right()
            } else {
                val errorBody = response.bodyAsText()
                logger.warn {
                    "Failed to create media container for Threads: ${response.status}, body: $errorBody"
                }
                RequestError(
                        status = response.status.value,
                        module = "threads",
                        errorMessage = "Failed to create media container",
                        body = ResponseBody(asString = errorBody),
                    )
                    .left()
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to create media container (threads) — uuid $imageUuid" }
            CaughtException(
                    status = 500,
                    module = "threads",
                    errorMessage = "Failed to create media container — uuid: $imageUuid",
                )
                .left()
        }
    }

    suspend fun createPost(request: NewPostRequest): ApiResult<NewPostResponse> {
        return try {
            request.validate()?.let { error ->
                return error.left()
            }

            val text =
                if (request.cleanupHtml == true) {
                    cleanupHtml(request.content)
                } else {
                    request.content.trim()
                } + if (request.link != null) "\n\n${request.link}" else ""

            logger.info { "Posting to Threads:\n${text.prependIndent("  |")}" }

            val imageIds =
                if (!request.images.isNullOrEmpty()) {
                    request.images.map { imageUuid ->
                        when (val uploadResult = createMediaContainer(imageUuid)) {
                            is Either.Left -> return uploadResult.value.left()
                            is Either.Right -> uploadResult.value
                        }
                    }
                } else {
                    emptyList()
                }

            val containerResponse =
                httpClient.post("${config.apiBase}/v1.0/${config.userId}/threads") {
                    parameter("media_type", "TEXT")
                    parameter("text", text)
                    if (imageIds.isNotEmpty()) {
                        imageIds.forEachIndexed { index, id -> parameter("children[$index]", id) }
                    }
                    parameter("access_token", config.accessToken)
                }

            if (containerResponse.status.value != 200) {
                val errorBody = containerResponse.bodyAsText()
                logger.warn {
                    "Failed to create Threads container: ${containerResponse.status}, body: $errorBody"
                }
                return RequestError(
                        status = containerResponse.status.value,
                        module = "threads",
                        errorMessage = "Failed to create media container",
                        body = ResponseBody(asString = errorBody),
                    )
                    .left()
            }

            val containerData = containerResponse.body<ThreadsMediaContainerResponse>()

            val publishResponse =
                httpClient.post("${config.apiBase}/v1.0/${config.userId}/threads_publish") {
                    parameter("creation_id", containerData.id)
                    parameter("access_token", config.accessToken)
                }

            if (publishResponse.status.value == 200) {
                val data = publishResponse.body<ThreadsPublishResponse>()
                NewThreadsPostResponse(id = data.id).right()
            } else {
                val errorBody = publishResponse.bodyAsText()
                logger.warn {
                    "Failed to publish to Threads: ${publishResponse.status}, body: $errorBody"
                }
                RequestError(
                        status = publishResponse.status.value,
                        module = "threads",
                        errorMessage = "Failed to publish post",
                        body = ResponseBody(asString = errorBody),
                    )
                    .left()
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to post to Threads" }
            CaughtException(
                    status = 500,
                    module = "threads",
                    errorMessage = "Failed to post to Threads: ${e.message}",
                )
                .left()
        }
    }

    suspend fun createPostRoute(call: ApplicationCall) {
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

        when (val result = createPost(request)) {
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
