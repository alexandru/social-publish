package socialpublish.backend.clients.metathreads

import arrow.core.Either
import arrow.core.raise.catch
import arrow.core.raise.either
import arrow.core.raise.ensure
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
import socialpublish.backend.common.NewMetaThreadsPostResponse
import socialpublish.backend.common.NewPostRequest
import socialpublish.backend.common.NewPostResponse
import socialpublish.backend.common.RequestError
import socialpublish.backend.common.ResponseBody
import socialpublish.backend.modules.FilesModule

private val logger = KotlinLogging.logger {}

class MetaThreadsApiModule(
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

        fun resource(filesModule: FilesModule): Resource<MetaThreadsApiModule> = resource {
            MetaThreadsApiModule(filesModule = filesModule, httpClient = defaultHttpClient().bind())
        }
    }

    private suspend fun createMediaContainer(
        config: MetaThreadsConfig,
        imageUuid: String,
    ): ApiResult<String> = either {
        catch({
            val imageUrl = filesModule.getFileUrl(imageUuid)

            val response =
                httpClient.post("${config.apiBase}/v1.0/${config.userId}/threads") {
                    parameter("media_type", "IMAGE")
                    parameter("image_url", imageUrl)
                    parameter("access_token", config.accessToken)
                }

            ensure(response.status.value == 200) {
                val errorBody = response.bodyAsText()
                logger.warn {
                    "Failed to create media container for Meta Threads: ${response.status}, body: $errorBody"
                }
                RequestError(
                    status = response.status.value,
                    module = "metathreads",
                    errorMessage = "Failed to create media container",
                    body = ResponseBody(asString = errorBody),
                )
            }

            val data = response.body<MetaThreadsMediaContainerResponse>()
            data.id
        }) { e: Exception ->
            logger.error(e) { "Failed to create media container (metathreads) — uuid $imageUuid" }
            raise(
                CaughtException(
                    status = 500,
                    module = "metathreads",
                    errorMessage = "Failed to create media container — uuid: $imageUuid",
                )
            )
        }
    }

    suspend fun createPost(
        config: MetaThreadsConfig,
        request: NewPostRequest,
    ): ApiResult<NewPostResponse> = either {
        catch({
            request.validate()?.let { error -> raise(error) }

            val text =
                if (request.cleanupHtml == true) {
                    cleanupHtml(request.content)
                } else {
                    request.content.trim()
                } + if (request.link != null) "\n\n${request.link}" else ""

            logger.info { "Posting to Meta Threads:\n${text.prependIndent("  |")}" }

            val imageIds =
                request.images?.map { imageUuid -> createMediaContainer(config, imageUuid).bind() }
                    ?: emptyList()

            val containerResponse =
                httpClient.post("${config.apiBase}/v1.0/${config.userId}/threads") {
                    parameter("media_type", "TEXT")
                    parameter("text", text)
                    if (imageIds.isNotEmpty()) {
                        imageIds.forEachIndexed { index, id -> parameter("children[$index]", id) }
                    }
                    parameter("access_token", config.accessToken)
                }

            ensure(containerResponse.status.value == 200) {
                val errorBody = containerResponse.bodyAsText()
                logger.warn {
                    "Failed to create Meta Threads container: ${containerResponse.status}, body: $errorBody"
                }
                RequestError(
                    status = containerResponse.status.value,
                    module = "metathreads",
                    errorMessage = "Failed to create post container",
                    body = ResponseBody(asString = errorBody),
                )
            }

            val containerData = containerResponse.body<MetaThreadsMediaContainerResponse>()

            val publishResponse =
                httpClient.post("${config.apiBase}/v1.0/${config.userId}/threads_publish") {
                    parameter("creation_id", containerData.id)
                    parameter("access_token", config.accessToken)
                }

            ensure(publishResponse.status.value == 200) {
                val errorBody = publishResponse.bodyAsText()
                logger.warn {
                    "Failed to publish to Meta Threads: ${publishResponse.status}, body: $errorBody"
                }
                RequestError(
                    status = publishResponse.status.value,
                    module = "metathreads",
                    errorMessage = "Failed to publish post",
                    body = ResponseBody(asString = errorBody),
                )
            }

            val data = publishResponse.body<MetaThreadsPublishResponse>()
            NewMetaThreadsPostResponse(id = data.id)
        }) { e: Exception ->
            logger.error(e) { "Failed to post to Meta Threads" }
            raise(
                CaughtException(
                    status = 500,
                    module = "metathreads",
                    errorMessage = "Failed to post to Meta Threads: ${e.message}",
                )
            )
        }
    }

    suspend fun createPostRoute(call: ApplicationCall, config: MetaThreadsConfig) {
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

        when (val result = createPost(config, request)) {
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

    suspend fun refreshAccessToken(
        config: MetaThreadsConfig
    ): ApiResult<MetaThreadsRefreshTokenResponse> = either {
        catch({
            val response =
                httpClient.post("${config.apiBase}/v1.0/access_token") {
                    parameter("grant_type", "th_refresh_token")
                    parameter("access_token", config.accessToken)
                }

            ensure(response.status.value == 200) {
                val errorBody = response.bodyAsText()
                logger.warn {
                    "Failed to refresh Meta Threads access token: ${response.status}, body: $errorBody"
                }
                RequestError(
                    status = response.status.value,
                    module = "metathreads",
                    errorMessage = "Failed to refresh access token",
                    body = ResponseBody(asString = errorBody),
                )
            }

            val tokenResponse = response.body<MetaThreadsRefreshTokenResponse>()
            logger.info { "Successfully refreshed Meta Threads access token" }
            tokenResponse
        }) { e: Exception ->
            logger.error(e) { "Failed to refresh Meta Threads access token" }
            raise(
                CaughtException(
                    status = 500,
                    module = "metathreads",
                    errorMessage = "Failed to refresh access token: ${e.message}",
                )
            )
        }
    }

    suspend fun refreshAccessTokenRoute(call: ApplicationCall, config: MetaThreadsConfig) {
        when (val result = refreshAccessToken(config)) {
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
