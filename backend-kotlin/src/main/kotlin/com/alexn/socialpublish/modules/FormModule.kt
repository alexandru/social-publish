package com.alexn.socialpublish.modules

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.alexn.socialpublish.integrations.bluesky.BlueskyApiModule
import com.alexn.socialpublish.integrations.mastodon.MastodonApiModule
import com.alexn.socialpublish.integrations.twitter.TwitterApiModule
import com.alexn.socialpublish.models.ApiError
import com.alexn.socialpublish.models.ApiResult
import com.alexn.socialpublish.models.NewPostRequest
import com.alexn.socialpublish.models.NewPostResponse
import com.alexn.socialpublish.models.ValidationError
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

private val logger = KotlinLogging.logger {}

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
    suspend fun broadcastPost(request: NewPostRequest): ApiResult<List<NewPostResponse>> {
        val targets = request.targets ?: emptyList()

        if (targets.isEmpty()) {
            return ValidationError(
                status = 400,
                errorMessage = "No targets specified",
                module = "form",
            ).left()
        }

        val results = mutableListOf<ApiResult<NewPostResponse>>()

        // Post to each target
        coroutineScope {
            for (target in targets) {
                val result =
                    async {
                        when (target.lowercase()) {
                            "mastodon" ->
                                mastodonModule?.createPost(request)
                                    ?: ValidationError(
                                        status = 503,
                                        errorMessage = "Mastodon integration not configured",
                                        module = "form",
                                    ).left()
                            "bluesky" ->
                                blueskyModule?.createPost(request)
                                    ?: ValidationError(
                                        status = 503,
                                        errorMessage = "Bluesky integration not configured",
                                        module = "form",
                                    ).left()
                            "twitter" ->
                                twitterModule?.createPost(request)
                                    ?: ValidationError(
                                        status = 503,
                                        errorMessage = "Twitter integration not configured",
                                        module = "form",
                                    ).left()
                            "linkedin", "rss" -> rssModule.createPost(request)
                            else -> {
                                ValidationError(
                                    status = 400,
                                    errorMessage = "Unknown target: $target",
                                    module = "form",
                                ).left()
                            }
                        }
                    }
                results.add(result.await())
            }
        }

        // Check if all succeeded
        val responses = mutableListOf<NewPostResponse>()
        val errors = mutableListOf<ApiError>()

        for (result in results) {
            when (result) {
                is Either.Right -> responses.add(result.value)
                is Either.Left -> errors.add(result.value)
            }
        }

        return if (errors.isEmpty()) {
            responses.right()
        } else if (responses.isEmpty()) {
            // All failed
            errors.first().left()
        } else {
            // Some succeeded, some failed - return what succeeded
            responses.right()
        }
    }

    /**
     * Handle broadcast POST HTTP route
     */
    suspend fun broadcastPostRoute(call: ApplicationCall) {
        val params = call.receiveParameters()

        // Parse targets from various formats
        val targets = mutableListOf<String>()
        params.getAll("targets[]")?.let { targets.addAll(it) }

        // Also check for individual target flags (mastodon=1, bluesky=1, etc.)
        if (params["mastodon"] == "1") targets.add("mastodon")
        if (params["bluesky"] == "1") targets.add("bluesky")
        if (params["twitter"] == "1") targets.add("twitter")
        if (params["linkedin"] == "1") targets.add("linkedin")
        if (params["rss"] == "1") targets.add("rss")

        val request =
            NewPostRequest(
                content = params["content"] ?: "",
                targets = targets.ifEmpty { null },
                link = params["link"],
                language = params["language"],
                cleanupHtml = params["cleanupHtml"]?.toBoolean(),
                images = params.getAll("images[]"),
            )

        when (val result = broadcastPost(request)) {
            is Either.Right -> {
                call.respond(
                    mapOf(
                        "success" to true,
                        "responses" to result.value,
                    ),
                )
            }
            is Either.Left -> {
                val error = result.value
                call.respond(
                    HttpStatusCode.fromValue(error.status),
                    mapOf("error" to error.errorMessage),
                )
            }
        }
    }
}
