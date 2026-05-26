package socialpublish.backend.server.routes

import arrow.core.getOrElse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import java.net.URLEncoder
import kotlinx.serialization.Serializable
import socialpublish.backend.clients.twitter.TwitterApiModule
import socialpublish.backend.clients.twitter.TwitterConfig
import socialpublish.backend.common.ErrorResponse
import socialpublish.backend.common.NewPostRequest
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
            is arrow.core.Either.Left -> {
                val error = result.value
                call.respond(
                    HttpStatusCode.fromValue(error.status),
                    ErrorResponse(error = error.errorMessage),
                )
            }
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
            call.respondRedirect(
                "/account?error=${URLEncoder.encode("Twitter authorization was incomplete. Please try again.", Charsets.UTF_8)}"
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
                call.response.header(
                    "Cache-Control",
                    "no-store, no-cache, must-revalidate, private",
                )
                call.response.header("Pragma", "no-cache")
                call.response.header("Expires", "0")
                call.respondRedirect("/account")
            }
            is arrow.core.Either.Left -> {
                val msg =
                    URLEncoder.encode(
                        "Twitter authorization failed. Please try again.",
                        Charsets.UTF_8,
                    )
                call.respondRedirect("/account?error=$msg")
            }
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
        call.respond(
            TwitterStatusResponse(
                hasAuthorization = row != null,
                createdAt = row?.createdAt?.toEpochMilli(),
            )
        )
    }

    context(_: UserSession)
    suspend fun createPostRoute(
        twitterConfig: TwitterConfig,
        call: ApplicationCall,
    ) {
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
