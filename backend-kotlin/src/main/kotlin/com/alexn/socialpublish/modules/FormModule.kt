package com.alexn.socialpublish.modules

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.alexn.socialpublish.integrations.bluesky.BlueskyApiModule
import com.alexn.socialpublish.integrations.mastodon.MastodonApiModule
import com.alexn.socialpublish.integrations.twitter.TwitterApiModule
import com.alexn.socialpublish.models.ApiError
import com.alexn.socialpublish.models.ApiResult
import com.alexn.socialpublish.models.CompositeError
import com.alexn.socialpublish.models.CompositeErrorResponse
import com.alexn.socialpublish.models.NewPostRequest
import com.alexn.socialpublish.models.NewPostResponse
import com.alexn.socialpublish.models.ValidationError
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Form module for broadcasting posts to multiple social media platforms
 */
class FormModule(
    private val mastodonModule: MastodonApiModule?,
    private val blueskyModule: BlueskyApiModule?,
    private val twitterModule: TwitterApiModule?,
    private val rssModule: RssModule,
) {
    /**
     * Broadcast post to multiple platforms
     */
    suspend fun broadcastPost(request: NewPostRequest): ApiResult<Map<String, NewPostResponse>> {
        val targets = request.targets?.map { it.lowercase() } ?: emptyList()
        val tasks = mutableListOf<suspend () -> ApiResult<NewPostResponse>>()

        tasks.add { rssModule.createPost(request) }

        if (targets.contains("mastodon")) {
            tasks.add {
                mastodonModule?.createPost(request)
                    ?: ValidationError(
                        status = 503,
                        errorMessage = "Mastodon integration not configured",
                        module = "form",
                    ).left()
            }
        }

        if (targets.contains("bluesky")) {
            tasks.add {
                blueskyModule?.createPost(request)
                    ?: ValidationError(
                        status = 503,
                        errorMessage = "Bluesky integration not configured",
                        module = "form",
                    ).left()
            }
        }

        if (targets.contains("twitter")) {
            tasks.add {
                twitterModule?.createPost(request)
                    ?: ValidationError(
                        status = 503,
                        errorMessage = "Twitter integration not configured",
                        module = "form",
                    ).left()
            }
        }

        val results =
            coroutineScope {
                tasks.map { task ->
                    async { task() }
                }.map { it.await() }
            }

        val errors = results.filterIsInstance<Either.Left<ApiError>>()
        if (errors.isNotEmpty()) {
            val status = errors.maxOf { it.value.status }
            val responsePayloads =
                results.map { result ->
                    when (result) {
                        is Either.Right ->
                            CompositeErrorResponse(
                                type = "success",
                                result = result.value,
                            )
                        is Either.Left ->
                            CompositeErrorResponse(
                                type = "error",
                                module = result.value.module,
                                status = result.value.status,
                                error = result.value.errorMessage,
                            )
                    }
                }
            return CompositeError(
                status = status,
                module = "form",
                errorMessage = "Failed to create post via ${errors.joinToString(", ") { it.value.module ?: "unknown" }}.",
                responses = responsePayloads,
            ).left()
        }

        val responseMap = mutableMapOf<String, NewPostResponse>()
        results.filterIsInstance<Either.Right<NewPostResponse>>().forEach { response ->
            responseMap[response.value.module] = response.value
        }

        return responseMap.right()
    }

    /**
     * Handle broadcast POST HTTP route
     */
    suspend fun broadcastPostRoute(call: ApplicationCall) {
        val request =
            runCatching {
                call.receive<NewPostRequest>()
            }.getOrNull()
                ?: run {
                    // If JSON receive failed, try form parameters. To avoid RequestAlreadyConsumedException,
                    // only attempt to read form parameters if content type is form data.
                    val contentTypeHeader = call.request.headers[HttpHeaders.ContentType]
                    val contentType = contentTypeHeader?.let { ContentType.parse(it) }
                    val params =
                        if (contentType?.match(ContentType.Application.FormUrlEncoded) == true ||
                            contentType?.match(ContentType.MultiPart.FormData) == true
                        ) {
                            call.receiveParameters()
                        } else {
                            null
                        }
                    val targets = mutableListOf<String>()
                    params?.getAll("targets[]")?.let { targets.addAll(it) }

                    if (params?.get("mastodon") == "1") targets.add("mastodon")
                    if (params?.get("bluesky") == "1") targets.add("bluesky")
                    if (params?.get("twitter") == "1") targets.add("twitter")
                    if (params?.get("linkedin") == "1") targets.add("linkedin")
                    if (params?.get("rss") == "1") targets.add("rss")

                    NewPostRequest(
                        content = params?.get("content") ?: "",
                        targets = targets.ifEmpty { null },
                        link = params?.get("link"),
                        language = params?.get("language"),
                        cleanupHtml = params?.get("cleanupHtml")?.toBoolean(),
                        images = params?.getAll("images[]"),
                    )
                }

        when (val result = broadcastPost(request)) {
            is Either.Right -> call.respond(result.value)
            is Either.Left -> {
                val error = result.value
                val payload =
                    if (error is CompositeError) {
                        mapOf(
                            "error" to error.errorMessage,
                            "responses" to error.responses,
                        )
                    } else {
                        mapOf("error" to error.errorMessage)
                    }
                call.respond(HttpStatusCode.fromValue(error.status), payload)
            }
        }
    }
}
