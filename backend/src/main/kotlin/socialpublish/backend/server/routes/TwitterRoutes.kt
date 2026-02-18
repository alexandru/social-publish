package socialpublish.backend.server.routes

import arrow.core.getOrElse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import java.util.UUID
import kotlinx.serialization.Serializable
import socialpublish.backend.clients.twitter.TwitterApiModule
import socialpublish.backend.clients.twitter.TwitterConfig
import socialpublish.backend.common.ErrorResponse
import socialpublish.backend.common.NewPostRequest
import socialpublish.backend.db.DocumentsDatabase
import socialpublish.backend.server.respondWithInternalServerError

@Serializable
data class TwitterStatusResponse(val hasAuthorization: Boolean, val createdAt: Long? = null)

class TwitterRoutes(
    private val twitterModule: TwitterApiModule,
    private val documentsDb: DocumentsDatabase,
) {
    suspend fun authorizeRoute(
        userUuid: UUID,
        twitterConfig: TwitterConfig,
        call: ApplicationCall,
    ) {
        val jwtToken =
            call.request.queryParameters["access_token"]
                ?: call.request.headers["Authorization"]?.removePrefix("Bearer ")
        if (jwtToken == null) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse(error = "Unauthorized"))
            return
        }
        when (val result = twitterModule.buildAuthorizeURL(twitterConfig, jwtToken)) {
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

    suspend fun callbackRoute(userUuid: UUID, twitterConfig: TwitterConfig, call: ApplicationCall) {
        val token = call.request.queryParameters["oauth_token"]
        val verifier = call.request.queryParameters["oauth_verifier"]

        if (token == null || verifier == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "Invalid request"))
            return
        }

        when (val result = twitterModule.saveOauthToken(twitterConfig, token, verifier, userUuid)) {
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
                val error = result.value
                call.respond(
                    HttpStatusCode.fromValue(error.status),
                    ErrorResponse(error = error.errorMessage),
                )
            }
        }
    }

    suspend fun statusRoute(userUuid: UUID, call: ApplicationCall) {
        val row =
            documentsDb.searchByKey("twitter-oauth-token:$userUuid").getOrElse { error ->
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

    suspend fun createPostRoute(
        userUuid: UUID,
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

        when (val result = twitterModule.createPost(twitterConfig, request, userUuid)) {
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
