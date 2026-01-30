package socialpublish.backend.modules

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import arrow.fx.coroutines.resourceScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import socialpublish.backend.clients.bluesky.BlueskyApiModule
import socialpublish.backend.clients.linkedin.LinkedInApiModule
import socialpublish.backend.clients.linkpreview.LinkPreview
import socialpublish.backend.clients.linkpreview.LinkPreviewParser
import socialpublish.backend.clients.mastodon.MastodonApiModule
import socialpublish.backend.clients.twitter.TwitterApiModule
import socialpublish.backend.common.ApiError
import socialpublish.backend.common.ApiResult
import socialpublish.backend.common.CompositeError
import socialpublish.backend.common.CompositeErrorResponse
import socialpublish.backend.common.NewPostRequest
import socialpublish.backend.common.NewPostResponse
import socialpublish.backend.common.ValidationError

/** Module for broadcasting posts to multiple social media platforms */
class PublishModule(
    private val mastodonModule: MastodonApiModule?,
    private val blueskyModule: BlueskyApiModule?,
    private val twitterModule: TwitterApiModule?,
    private val linkedInModule: LinkedInApiModule?,
    private val rssModule: RssModule,
    private val linkPreviewParser: LinkPreviewParser,
) {
    /** Broadcast post to multiple platforms */
    suspend fun broadcastPost(request: NewPostRequest): ApiResult<Map<String, NewPostResponse>> =
        resourceScope {
            val targets = request.targets?.map { it.lowercase() } ?: emptyList()

            // Fetch and optimize link preview once if needed
            val linkPreview: LinkPreview? =
                if (
                    request.link != null &&
                        (targets.contains("bluesky") || targets.contains("linkedin"))
                ) {
                    linkPreviewParser.fetchPreviewWithImage(request.link).bind()
                } else {
                    null
                }

            val tasks = mutableListOf<suspend () -> ApiResult<NewPostResponse>>()

            // Only publish to RSS if explicitly requested
            if (targets.contains("rss")) {
                tasks.add { rssModule.createPost(request) }
            }

            if (targets.contains("mastodon")) {
                tasks.add {
                    mastodonModule?.createPost(request)
                        ?: ValidationError(
                                status = 503,
                                errorMessage = "Mastodon integration not configured",
                                module = "publish",
                            )
                            .left()
                }
            }

            if (targets.contains("bluesky")) {
                tasks.add {
                    blueskyModule?.createPost(request, linkPreview)
                        ?: ValidationError(
                                status = 503,
                                errorMessage = "Bluesky integration not configured",
                                module = "publish",
                            )
                            .left()
                }
            }

            if (targets.contains("twitter")) {
                tasks.add {
                    twitterModule?.createPost(request)
                        ?: ValidationError(
                                status = 503,
                                errorMessage = "Twitter integration not configured",
                                module = "publish",
                            )
                            .left()
                }
            }

            if (targets.contains("linkedin")) {
                tasks.add {
                    linkedInModule?.createPost(request, linkPreview)
                        ?: ValidationError(
                                status = 503,
                                errorMessage = "LinkedIn integration not configured",
                                module = "publish",
                            )
                            .left()
                }
            }

            val results = coroutineScope {
                // Run all tasks concurrently
                tasks.map { task -> async { task() } }.awaitAll()
            }

            val errors = results.filterIsInstance<Either.Left<ApiError>>()
            if (errors.isNotEmpty()) {
                val status = errors.maxOf { it.value.status }
                val responsePayloads =
                    results.map { result ->
                        when (result) {
                            is Either.Right ->
                                CompositeErrorResponse(type = "success", result = result.value)
                            is Either.Left ->
                                CompositeErrorResponse(
                                    type = "error",
                                    module = result.value.module,
                                    status = result.value.status,
                                    error = result.value.errorMessage,
                                )
                        }
                    }
                return@resourceScope CompositeError(
                        status = status,
                        module = "publish",
                        errorMessage =
                            "Failed to create post via ${errors.joinToString(", ") { it.value.module ?: "unknown" }}.",
                        responses = responsePayloads,
                    )
                    .left()
            }

            val responseMap = mutableMapOf<String, NewPostResponse>()
            results.filterIsInstance<Either.Right<NewPostResponse>>().forEach { response ->
                responseMap[response.value.module] = response.value
            }

            responseMap.right()
        }
}
