package socialpublish.backend.server.routes

import arrow.core.getOrElse
import arrow.core.nonEmptyListOf
import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import kotlinx.serialization.Serializable
import socialpublish.backend.clients.linkedin.LinkedInApiModule
import socialpublish.backend.clients.linkedin.LinkedInConfig
import socialpublish.backend.common.ErrorResponse
import socialpublish.backend.common.NewPostRequest
import socialpublish.backend.common.NewPostRequestMessage
import socialpublish.backend.db.DocumentsDatabase
import socialpublish.backend.db.UUIDv7
import socialpublish.backend.server.respondWithInternalServerError

@Serializable
data class LinkedInStatusResponse(
    val hasAuthorization: Boolean,
    val createdAt: Long? = null,
)

class LinkedInRoutes(
    private val linkedInModule: LinkedInApiModule,
    private val documentsDb: DocumentsDatabase,
) {
    suspend fun authorizeRoute(
        linkedInConfig: LinkedInConfig,
        call: ApplicationCall,
    ) {
        val state = linkedInModule.generateOAuthState()

        call.setOAuthStateCookie(state, maxAge = 600)

        when (
            val result = linkedInModule.buildAuthorizeURL(linkedInConfig, state)
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
        linkedInConfig: LinkedInConfig,
        call: ApplicationCall,
    ) {
        val code = call.request.queryParameters["code"]
        val callbackState = call.request.queryParameters["state"]
        val error = call.request.queryParameters["error"]
        val errorDescription = call.request.queryParameters["error_description"]

        val cookieState = call.request.cookies["linkedin-oauth-state"]
        call.setOAuthStateCookie("", maxAge = 0)

        if (error != null) {
            val userMessage =
                when (error) {
                    "user_cancelled_login" -> "LinkedIn login was cancelled."
                    "user_cancelled_authorize" ->
                        "LinkedIn authorization was declined."
                    else -> errorDescription ?: "LinkedIn authorization failed."
                }
            call.redirectToAccountError(userMessage)
            return
        }

        if (callbackState.isNullOrBlank()) {
            call.redirectToLinkedInVerificationError()
            return
        }

        if (cookieState != callbackState) {
            call.redirectToLinkedInVerificationError()
            return
        }

        if (code == null) {
            call.redirectToAccountError(
                "LinkedIn did not return an authorization code. Please try again."
            )
            return
        }

        when (
            val tokenResult =
                linkedInModule.exchangeCodeForToken(
                    linkedInConfig,
                    code,
                    "${linkedInModule.baseUrl}/api/linkedin/callback",
                )
        ) {
            is arrow.core.Either.Right -> {
                when (
                    val saveResult =
                        linkedInModule.saveOAuthToken(
                            tokenResult.value,
                            userUuid,
                        )
                ) {
                    is arrow.core.Either.Right -> {
                        call.preventOAuthRedirectCaching()
                        call.respondRedirect("/account")
                    }
                    is arrow.core.Either.Left -> {
                        call.redirectToLinkedInAuthorizationFailed()
                    }
                }
            }
            is arrow.core.Either.Left -> {
                call.redirectToLinkedInAuthorizationFailed()
            }
        }
    }

    suspend fun statusRoute(userUuid: UUIDv7, call: ApplicationCall) {
        val row =
            documentsDb
                .searchByKey("linkedin-oauth-token:$userUuid", userUuid)
                .getOrElse { error ->
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
        userUuid: UUIDv7,
        linkedInConfig: LinkedInConfig,
        call: ApplicationCall,
    ) {
        val request =
            runCatching { call.receive<NewPostRequest>() }.getOrNull()
                ?: run {
                    val params = call.receiveParameters()
                    NewPostRequest(
                        targets = params.getAll("targets"),
                        language = params["language"],
                        messages =
                            nonEmptyListOf(
                                NewPostRequestMessage(
                                    content = params["content"] ?: "",
                                    link = params["link"],
                                    images = params.getAll("images"),
                                )
                            ),
                    )
                }

        when (
            val result =
                linkedInModule.createThread(linkedInConfig, request, userUuid)
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

    private fun ApplicationCall.setOAuthStateCookie(
        value: String,
        maxAge: Int,
    ) {
        response.cookies.append(
            Cookie(
                name = "linkedin-oauth-state",
                value = value,
                maxAge = maxAge,
                path = "/",
                httpOnly = true,
                extensions = mapOf("SameSite" to "Lax"),
            )
        )
    }

    private suspend fun ApplicationCall.redirectToLinkedInVerificationError() =
        redirectToAccountError(
            "LinkedIn authorization could not be verified. Please try again."
        )

    private suspend fun ApplicationCall
        .redirectToLinkedInAuthorizationFailed() =
        redirectToAccountError(
            "LinkedIn authorization failed. Please try again."
        )
}
