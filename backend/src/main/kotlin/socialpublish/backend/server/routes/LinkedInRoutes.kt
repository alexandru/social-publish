package socialpublish.backend.server.routes

import arrow.core.getOrElse
import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import kotlinx.serialization.Serializable
import socialpublish.backend.clients.linkedin.LinkedInApiModule
import socialpublish.backend.clients.linkedin.LinkedInConfig
import socialpublish.backend.common.ErrorResponse
import socialpublish.backend.db.DocumentsDatabase
import socialpublish.backend.db.UserSession
import socialpublish.backend.server.respondWithInternalServerError
import socialpublish.backend.server.userUuid

@Serializable
data class LinkedInStatusResponse(
    val hasAuthorization: Boolean,
    val createdAt: Long? = null,
)

class LinkedInRoutes(
    private val linkedInModule: LinkedInApiModule,
    private val documentsDb: DocumentsDatabase,
) {
    context(_: UserSession)
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

    context(_: UserSession)
    suspend fun callbackRoute(
        linkedInConfig: LinkedInConfig,
        call: ApplicationCall,
    ) {
        val userUuid = userUuid()
        val code = call.request.queryParameters["code"]
        val callbackState = call.request.queryParameters["state"]
        val error = call.request.queryParameters["error"]
        val errorDescription = call.request.queryParameters["error_description"]

        // Clear the OAuth state cookie
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
                    is arrow.core.Either.Left ->
                        call.redirectToLinkedInAuthorizationFailed()
                }
            }
            is arrow.core.Either.Left ->
                call.redirectToLinkedInAuthorizationFailed()
        }
    }

    context(_: UserSession)
    suspend fun statusRoute(call: ApplicationCall) {
        val userUuid = userUuid()
        val row =
            documentsDb
                .searchByKey("linkedin-oauth-token:$userUuid")
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

    context(_: UserSession)
    suspend fun createPostRoute(
        linkedInConfig: LinkedInConfig,
        call: ApplicationCall,
    ) {
        val request = call.receiveNewPostRequest()

        when (val result = linkedInModule.createPost(linkedInConfig, request)) {
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
