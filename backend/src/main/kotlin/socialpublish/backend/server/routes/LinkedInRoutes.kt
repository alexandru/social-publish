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
import java.util.UUID
import kotlinx.serialization.Serializable
import socialpublish.backend.clients.linkedin.LinkedInApiModule
import socialpublish.backend.clients.linkedin.LinkedInConfig
import socialpublish.backend.common.ErrorResponse
import socialpublish.backend.common.NewPostRequest
import socialpublish.backend.db.DocumentsDatabase
import socialpublish.backend.server.respondWithInternalServerError

@Serializable
data class LinkedInStatusResponse(val hasAuthorization: Boolean, val createdAt: Long? = null)

class LinkedInRoutes(
    private val linkedInModule: LinkedInApiModule,
    private val documentsDb: DocumentsDatabase,
) {
    suspend fun authorizeRoute(
        userUuid: UUID,
        linkedInConfig: LinkedInConfig,
        callbackJwtToken: String,
        call: ApplicationCall,
    ) {
        when (
            val result =
                linkedInModule.buildAuthorizeURL(linkedInConfig, callbackJwtToken, userUuid)
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
        userUuid: UUID,
        linkedInConfig: LinkedInConfig,
        call: ApplicationCall,
    ) {
        val code = call.request.queryParameters["code"]
        val state = call.request.queryParameters["state"]
        val accessToken = call.request.queryParameters["access_token"]
        val error = call.request.queryParameters["error"]
        val errorDescription = call.request.queryParameters["error_description"]

        if (error != null) {
            val userMessage =
                when (error) {
                    "user_cancelled_login" -> "You cancelled the LinkedIn login"
                    "user_cancelled_authorize" -> "You declined the LinkedIn authorization request"
                    else -> errorDescription ?: "LinkedIn authorization failed: $error"
                }
            call.respondRedirect("/account?error=${URLEncoder.encode(userMessage, "UTF-8")}")
            return
        }

        if (state != null) {
            val storedJwtToken = linkedInModule.verifyOAuthState(state, userUuid)
            if (storedJwtToken == null) {
                val msg =
                    URLEncoder.encode(
                        "Authorization failed: Invalid state parameter. Please try again.",
                        "UTF-8",
                    )
                call.respondRedirect("/account?error=$msg")
                return
            }
        }

        if (code == null) {
            val msg = URLEncoder.encode("LinkedIn authorization failed: missing code", "UTF-8")
            call.respondRedirect("/account?error=$msg")
            return
        }

        val redirectUri =
            if (accessToken != null) {
                "${linkedInModule.baseUrl}/api/linkedin/callback?access_token=${URLEncoder.encode(accessToken, "UTF-8")}"
            } else {
                "${linkedInModule.baseUrl}/api/linkedin/callback"
            }

        when (
            val tokenResult = linkedInModule.exchangeCodeForToken(linkedInConfig, code, redirectUri)
        ) {
            is arrow.core.Either.Right -> {
                when (val saveResult = linkedInModule.saveOAuthToken(tokenResult.value, userUuid)) {
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
                        val msg = URLEncoder.encode(saveResult.value.errorMessage, "UTF-8")
                        call.respondRedirect("/account?error=$msg")
                    }
                }
            }
            is arrow.core.Either.Left -> {
                val msg = URLEncoder.encode(tokenResult.value.errorMessage, "UTF-8")
                call.respondRedirect("/account?error=$msg")
            }
        }
    }

    suspend fun statusRoute(userUuid: UUID, call: ApplicationCall) {
        val row =
            documentsDb.searchByKey("linkedin-oauth-token:$userUuid", userUuid).getOrElse { error ->
                call.respondWithInternalServerError(error)
                return
            }
        call.respond(
            LinkedInStatusResponse(
                hasAuthorization = row != null,
                createdAt = row?.createdAt?.toEpochMilli(),
            )
        )
    }

    suspend fun createPostRoute(
        userUuid: UUID,
        linkedInConfig: LinkedInConfig,
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

        when (val result = linkedInModule.createPost(linkedInConfig, request, userUuid)) {
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
