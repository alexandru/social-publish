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
import socialpublish.backend.db.UserSession
import socialpublish.backend.server.respondWithInternalServerError
import socialpublish.backend.server.userUuid

@Serializable
data class TwitterStatusResponse(
    val hasAuthorization: Boolean,
    val createdAt: Long? = null,
)

class TwitterRoutes(
    private val twitterModule: TwitterApiModule,
    private val documentsDb: DocumentsDatabase,
) {
    context(_: UserSession)
    suspend fun authorizeRoute(
        twitterConfig: TwitterConfig,
        call: ApplicationCall,
    ) {
        val userUuid = userUuid()
        when (
            val result =
                twitterModule.buildAuthorizeURL(twitterConfig, userUuid)
        ) {
            is arrow.core.Either.Right -> call.respondRedirect(result.value)
            is arrow.core.Either.Left ->
                call.redirectToAccountError(result.value.errorMessage)
        }
    }

    context(_: UserSession)
    suspend fun callbackRoute(
        twitterConfig: TwitterConfig,
        call: ApplicationCall,
    ) {
        val userUuid = userUuid()
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
                call.redirectToAccountInfo("Twitter connected successfully.")
            }
            is arrow.core.Either.Left ->
                call.redirectToAccountError(
                    "Twitter authorization failed. Please try again."
                )
        }
    }

    context(_: UserSession)
    suspend fun statusRoute(call: ApplicationCall) {
        val userUuid = userUuid()
        val row =
            documentsDb
                .searchByKey("twitter-oauth-token:$userUuid")
                .getOrElse { error ->
                    call.respondWithInternalServerError(error)
                    return
                }
        val hasAuthorization =
            row != null && twitterModule.hasValidAccessToken(row.payload)
        call.respond(
            TwitterStatusResponse(
                hasAuthorization = hasAuthorization,
                createdAt = row?.createdAt?.toEpochMilli(),
            )
        )
    }

    context(_: UserSession)
    suspend fun createPostRoute(
        twitterConfig: TwitterConfig,
        call: ApplicationCall,
    ) {
        val request = call.receiveNewPostRequest()

        when (val result = twitterModule.createPost(twitterConfig, request)) {
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
