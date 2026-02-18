package socialpublish.backend.modules

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import socialpublish.backend.clients.bluesky.BlueskyApiModule
import socialpublish.backend.clients.bluesky.BlueskyConfig
import socialpublish.backend.clients.linkedin.LinkedInApiModule
import socialpublish.backend.clients.linkedin.LinkedInConfig
import socialpublish.backend.clients.mastodon.MastodonApiModule
import socialpublish.backend.clients.mastodon.MastodonConfig
import socialpublish.backend.clients.twitter.TwitterApiModule
import socialpublish.backend.clients.twitter.TwitterConfig
import socialpublish.backend.common.ApiError
import socialpublish.backend.common.ApiResult
import socialpublish.backend.common.CompositeError
import socialpublish.backend.common.CompositeErrorResponse
import socialpublish.backend.common.NewPostRequest
import socialpublish.backend.common.NewPostResponse
import socialpublish.backend.common.ValidationError

/**
 * Module for broadcasting posts to multiple social media platforms.
 *
 * Each social module is shared at the server level; per-user config is passed per call.
 */
class PublishModule(
    private val mastodonModule: MastodonApiModule?,
    private val mastodonConfig: MastodonConfig?,
    private val blueskyModule: BlueskyApiModule?,
    private val blueskyConfig: BlueskyConfig?,
    private val twitterModule: TwitterApiModule?,
    private val twitterConfig: TwitterConfig?,
    private val linkedInModule: LinkedInApiModule?,
    private val linkedInConfig: LinkedInConfig?,
    private val rssModule: RssModule,
    private val userUuid: UUID,
) {
    /** Broadcast post to multiple platforms */
    suspend fun broadcastPost(request: NewPostRequest): ApiResult<Map<String, NewPostResponse>> {
        val targets = request.targets?.map { it.lowercase() } ?: emptyList()
        val tasks = mutableListOf<suspend () -> ApiResult<NewPostResponse>>()

        if (targets.contains("rss")) {
            tasks.add { rssModule.createPost(request, userUuid) }
        }

        if (targets.contains("mastodon")) {
            tasks.add {
                val mod = mastodonModule
                val cfg = mastodonConfig
                if (mod != null && cfg != null) {
                    mod.createPost(cfg, request)
                } else {
                    ValidationError(
                            status = 503,
                            errorMessage = "Mastodon integration not configured",
                            module = "publish",
                        )
                        .left()
                }
            }
        }

        if (targets.contains("bluesky")) {
            tasks.add {
                val mod = blueskyModule
                val cfg = blueskyConfig
                if (mod != null && cfg != null) {
                    mod.createPost(cfg, request)
                } else {
                    ValidationError(
                            status = 503,
                            errorMessage = "Bluesky integration not configured",
                            module = "publish",
                        )
                        .left()
                }
            }
        }

        if (targets.contains("twitter")) {
            tasks.add {
                val mod = twitterModule
                val cfg = twitterConfig
                if (mod != null && cfg != null) {
                    mod.createPost(cfg, request, userUuid)
                } else {
                    ValidationError(
                            status = 503,
                            errorMessage = "Twitter integration not configured",
                            module = "publish",
                        )
                        .left()
                }
            }
        }

        if (targets.contains("linkedin")) {
            tasks.add {
                val mod = linkedInModule
                val cfg = linkedInConfig
                if (mod != null && cfg != null) {
                    mod.createPost(cfg, request, userUuid)
                } else {
                    ValidationError(
                            status = 503,
                            errorMessage = "LinkedIn integration not configured",
                            module = "publish",
                        )
                        .left()
                }
            }
        }

        val results = coroutineScope {
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
                                error =
                                    CompositeError(
                                        module = result.value.module,
                                        errorMessage = result.value.errorMessage,
                                    ),
                            )
                    }
                }
            CompositeError(
                    module = "publish",
                    errorMessage = "Some platforms failed",
                    responses = responsePayloads,
                    status = status,
                )
                .left()
        } else {
            val successResults =
                results.filterIsInstance<Either.Right<NewPostResponse>>().map { it.value }
            buildMap {
                    targets.zip(successResults).forEach { (target, result) -> put(target, result) }
                }
                .right()
        }
    }
}
