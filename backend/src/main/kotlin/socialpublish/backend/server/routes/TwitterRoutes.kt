package socialpublish.backend.server.routes

import arrow.core.getOrElse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import kotlinx.serialization.Serializable
import socialpublish.backend.clients.twitter.TwitterApiModule
import socialpublish.backend.clients.twitter.TwitterConfig
import socialpublish.backend.common.ErrorResponse
import socialpublish.backend.db.DocumentsDatabase
import socialpublish.backend.db.UUIDv7
import socialpublish.backend.server.respondWithInternalServerError

@Serializable
data class TwitterStatusResponse(
    val hasAuthorization: Boolean,
    val createdAt: Long? = null,
)

class TwitterRoutes(
    private val twitterModule: TwitterApiModule,
    private val documentsDb: DocumentsDatabase,
) {
    suspend fun authorizeRoute(
        userUuid: UUIDv7,
        twitterConfig: TwitterConfig,
        call: ApplicationCall,
    ) {
        when (
            val result =
                twitterModule.buildAuthorizeURL(twitterConfig, userUuid)
        ) {
            is arrow.core.Either.Right -> call.respondRedirect(result.value)
            is arrow.core.Either.Left -> {
                val error = result.value
                call.respond(
                    HttpStatusCode.fromValue(error.status),
                    ErrorResponse(error = error.errorMessage),
                )
            }
        }
    }

    suspend fun callbackRoute(
        userUuid: UUIDv7,
        twitterConfig: TwitterConfig,
        call: ApplicationCall,
    ) {
        val token = call.request.queryParameters["oauth_token"]
        val verifier = call.request.queryParameters["oauth_verifier"]

        if (token == null || verifier == null) {
            call.redirectToAccountError(
                "Twitter authorization was incomplete. Please try again."
            )
            return
        }

        when (
            val result =
                twitterModule.saveOauthToken(
                    twitterConfig,
                    token,
                    verifier,
                    userUuid,
                )
        ) {
            is arrow.core.Either.Right -> {
                call.preventOAuthRedirectCaching()
                call.respondRedirect("/account")
            }
            is arrow.core.Either.Left -> {
                call.redirectToAccountError(
                    "Twitter authorization failed. Please try again."
                )
            }
        }
    }

    suspend fun statusRoute(userUuid: UUIDv7, call: ApplicationCall) {
        val row =
            documentsDb
                .searchByKey("twitter-oauth-token:$userUuid", userUuid)
                .getOrElse { error ->
                    call.respondWithInternalServerError(error)
                    return
                }
        call.respond(
            TwitterStatusResponse(
                hasAuthorization =
                    row != null &&
                        twitterModule.hasValidAccessToken(row.payload),
                createdAt = row?.createdAt?.toEpochMilli(),
            )
        )
    }

    suspend fun createPostRoute(
        userUuid: UUIDv7,
        twitterConfig: TwitterConfig,
        call: ApplicationCall,
    ) {
        val request = call.receiveNewPostRequest()

        when (
            val result =
                twitterModule.createThread(twitterConfig, request, userUuid)
        ) {
            is arrow.core.Either.Right -> call.respond(result.value)
            is arrow.core.Either.Left -> {
                val error = result.value
                call.respond(
                    HttpStatusCode.fromValue(error.status),
                    ErrorResponse(error = error.errorMessage),
                )
            }
        }
    }
}
