package socialpublish.backend.modules

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.context.either
import arrow.core.raise.context.raise
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
import socialpublish.backend.common.Target
import socialpublish.backend.common.ValidationError
import socialpublish.backend.db.UserSession
import socialpublish.backend.server.userUuid

/**
 * Module for broadcasting posts to multiple social media platforms.
 *
 * Each social module is shared at the server level; per-user config is passed
 * per call.
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
    private val feedModule: FeedModule,
    private val userSession: UserSession,
) {
    /** Broadcast post to multiple platforms */
    context(_: UserSession)
    suspend fun broadcastPost(
        request: NewPostRequest
    ): ApiResult<Map<String, NewPostResponse>> = either {
        val targets = request.targets ?: emptyList()

        // Validate targets are non-empty
        if (targets.isEmpty()) {
            raise(
                ValidationError(
                    status = 400,
                    module = "publish",
                    errorMessage = "No publish targets selected",
                )
            )
        }

        // Preflight validation for all selected targets
        if (Target.LinkedIn in targets && request.messages.size > 2) {
            raise(
                ValidationError(
                    status = 400,
                    module = "publish",
                    errorMessage =
                        "LinkedIn allows at most two messages (one post and one follow-up comment)",
                )
            )
        }
        if (Target.Feed in targets) {
            feedModule.validateRequest(request)?.let { raise(it) }
        }
        if (Target.LinkedIn in targets) {
            linkedInModule?.validateRequest(request)?.let { raise(it) }
        }
        if (Target.Bluesky in targets) {
            blueskyModule?.validateRequest(request)?.let { raise(it) }
        }
        if (Target.Mastodon in targets) {
            mastodonModule?.validateRequest(request)?.let { raise(it) }
        }
        if (Target.Twitter in targets) {
            twitterModule?.validateRequest(request)?.let { raise(it) }
        }

        val tasks = mutableListOf<suspend () -> ApiResult<NewPostResponse>>()
        val taskTargets = mutableListOf<String>()

        if (Target.Feed in targets) {
            tasks.add {
                feedModule.createPosts(
                    targets = request.targets ?: listOf(Target.Feed),
                    language = request.language,
                    messages = request.messages,
                    userUuid = userUuid(),
                )
            }
            taskTargets.add("feed")
        }

        if (Target.Mastodon in targets) {
            tasks.add {
                val mod = mastodonModule
                val cfg = mastodonConfig
                if (mod != null && cfg != null) {
                    mod.createThread(cfg, request)
                } else {
                    ValidationError(
                            status = 503,
                            errorMessage =
                                "Mastodon integration not configured",
                            module = "publish",
                        )
                        .left()
                }
            }
            taskTargets.add("mastodon")
        }

        if (Target.Bluesky in targets) {
            tasks.add {
                val mod = blueskyModule
                val cfg = blueskyConfig
                if (mod != null && cfg != null) {
                    mod.createThread(cfg, request)
                } else {
                    ValidationError(
                            status = 503,
                            errorMessage = "Bluesky integration not configured",
                            module = "publish",
                        )
                        .left()
                }
            }
            taskTargets.add("bluesky")
        }

        if (Target.Twitter in targets) {
            tasks.add {
                val mod = twitterModule
                val cfg = twitterConfig
                if (mod != null && cfg != null) {
                    mod.createThread(cfg, request)
                } else {
                    ValidationError(
                            status = 503,
                            errorMessage = "Twitter integration not configured",
                            module = "publish",
                        )
                        .left()
                }
            }
            taskTargets.add("twitter")
        }

        if (Target.LinkedIn in targets) {
            tasks.add {
                val mod = linkedInModule
                val cfg = linkedInConfig
                if (mod != null && cfg != null) {
                    mod.createThread(cfg, request)
                } else {
                    ValidationError(
                            status = 503,
                            errorMessage =
                                "LinkedIn integration not configured",
                            module = "publish",
                        )
                        .left()
                }
            }
            taskTargets.add("linkedin")
        }

        val results = coroutineScope {
            tasks.map { task -> async { task() } }.awaitAll()
        }

        val errors = results.filterIsInstance<Either.Left<ApiError>>()
        if (errors.isNotEmpty()) {
            val status = errors.maxOf { it.value.status }
            val responsePayloads = results.map { result ->
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
                            error = result.value.errorMessage,
                        )
                }
            }
            raise(
                CompositeError(
                    module = "publish",
                    errorMessage = "Failed to publish to some platforms",
                    responses = responsePayloads,
                    status = status,
                )
            )
        } else {
            val successResults =
                results.filterIsInstance<Either.Right<NewPostResponse>>().map {
                    it.value
                }
            buildMap {
                taskTargets.zip(successResults).forEach { (target, result) ->
                    put(target, result)
                }
            }
        }
    }
}
