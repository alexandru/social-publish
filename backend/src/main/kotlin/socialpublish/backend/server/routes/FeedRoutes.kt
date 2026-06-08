package socialpublish.backend.server.routes

import arrow.core.Either
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import socialpublish.backend.common.ErrorResponse
import socialpublish.backend.db.UUIDv7
import socialpublish.backend.db.UserSession
import socialpublish.backend.modules.FeedModule

class FeedRoutes(private val feedModule: FeedModule) {
    /** Handle feed post creation HTTP route */
    context(_: UserSession)
    suspend fun createPostRoute(call: ApplicationCall) {
        val request = call.receiveNewPostRequestOrRespond() ?: return

        when (val result = feedModule.createPost(request)) {
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

    /** Handle feed generation HTTP route */
    suspend fun generateFeedRoute(call: ApplicationCall) {
        val userUuid =
            call.parameters["userUuid"]?.let {
                runCatching { UUIDv7.fromString(it) }.getOrNull()
            }
                ?: run {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(error = "Missing userUuid"),
                    )
                    return
                }
        val target = call.parameters["target"]
        val filterByLinks = call.request.queryParameters["filterByLinks"]
        val filterByImages = call.request.queryParameters["filterByImages"]

        val feedContent =
            feedModule.generateFeed(
                userUuid,
                filterByLinks,
                filterByImages,
                target,
            )
        call.respondText(feedContent, ContentType.parse("application/atom+xml"))
    }

    /** Get feed item by UUID */
    suspend fun getFeedItem(call: ApplicationCall) {
        val userUuid =
            call.parameters["userUuid"]?.let {
                runCatching { UUIDv7.fromString(it) }.getOrNull()
            }
                ?: run {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(error = "Missing userUuid"),
                    )
                    return
                }
        val uuid =
            call.parameters["uuid"]
                ?: run {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(error = "Missing UUID"),
                    )
                    return
                }

        val post = feedModule.getFeedItemByUuid(userUuid, uuid)
        if (post == null) {
            call.respond(
                HttpStatusCode.NotFound,
                ErrorResponse(error = "Post not found"),
            )
            return
        }

        call.respond(post)
    }
}
