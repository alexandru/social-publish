package socialpublish.backend.server.routes

import arrow.core.getOrElse
import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import java.net.URLEncoder
import kotlinx.serialization.Serializable
import socialpublish.backend.clients.linkedin.LinkedInApiModule
import socialpublish.backend.clients.linkedin.LinkedInConfig
import socialpublish.backend.common.ErrorResponse
import socialpublish.backend.common.NewPostRequest
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
        val userUuid = userUuid()
        val state = linkedInModule.generateOAuthState()

        call.response.cookies.append(
            Cookie(
                name = "linkedin-oauth-state",
                value = state,
                maxAge = 600,
                path = "/",
                httpOnly = true,
            )
        )

        when (
            val result =
                linkedInModule.buildAuthorizeURL(
                    linkedInConfig,
                    userUuid,
                    state,
                )
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
        call.response.cookies.append(
            Cookie(
                name = "linkedin-oauth-state",
                value = "",
                maxAge = 0,
                path = "/",
                httpOnly = true,
            )
        )

        if (error != null) {
            val userMessage =
                when (error) {
                    "user_cancelled_login" -> "LinkedIn login was cancelled."
                    "user_cancelled_authorize" ->
                        "LinkedIn authorization was declined."
                    else -> errorDescription ?: "LinkedIn authorization failed."
                }
            call.respondRedirect(
                "/account?error=${URLEncoder.encode(userMessage, Charsets.UTF_8)}"
            )
            return
        }

        if (callbackState.isNullOrBlank()) {
            val msg =
                URLEncoder.encode(
                    "LinkedIn authorization could not be verified. Please try again.",
                    Charsets.UTF_8,
                )
            call.respondRedirect("/account?error=$msg")
            return
        }

        if (cookieState != callbackState) {
            val msg =
                URLEncoder.encode(
                    "LinkedIn authorization could not be verified. Please try again.",
                    Charsets.UTF_8,
                )
            call.respondRedirect("/account?error=$msg")
            return
        }

        if (code == null) {
            val msg =
                URLEncoder.encode(
                    "LinkedIn did not return an authorization code. Please try again.",
                    Charsets.UTF_8,
                )
            call.respondRedirect("/account?error=$msg")
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
                                "LinkedIn authorization failed. Please try again.",
                                Charsets.UTF_8,
                            )
                        call.respondRedirect("/account?error=$msg")
                    }
                }
            }
            is arrow.core.Either.Left -> {
                val msg =
                    URLEncoder.encode(
                        "LinkedIn authorization failed. Please try again.",
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
}
