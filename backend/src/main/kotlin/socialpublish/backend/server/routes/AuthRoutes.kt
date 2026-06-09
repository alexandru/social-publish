package socialpublish.backend.server.routes

import arrow.core.getOrElse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.*
import io.ktor.server.request.contentType
import io.ktor.server.request.receive
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import socialpublish.backend.common.ErrorResponse
import socialpublish.backend.common.loggerFactory
import socialpublish.backend.db.DocumentsDatabase
import socialpublish.backend.db.UserSession
import socialpublish.backend.modules.AuthService
import socialpublish.backend.server.putUserSession
import socialpublish.backend.server.respondApiError
import socialpublish.backend.server.respondWithUnauthorized

const val AUTH_SESSION = "auth-session"

@Serializable
data class LoginRequest(val username: String, val password: String)

/**
 * Services configured and ready to use for the authenticated user.
 *
 * For Mastodon and Bluesky: true when credentials are stored. For Twitter and
 * LinkedIn: true when credentials are stored AND the OAuth flow has been
 * completed (i.e. the OAuth token is in the database). LLM is a utility
 * integration (alt-text generation), not a posting target.
 */
@Serializable
data class ConfiguredServices(
    val mastodon: Boolean = false,
    val bluesky: Boolean = false,
    val twitter: Boolean = false,
    val linkedin: Boolean = false,
    val llm: Boolean = false,
)

@Serializable
data class LoginResponse(
    val token: String,
    val configuredServices: ConfiguredServices,
)

@Serializable
data class UserResponse(
    val username: String,
    val configuredServices: ConfiguredServices,
    val userUuid: String,
)

suspend fun withSession(
    call: ApplicationCall,
    block:
        suspend context(UserSession)
        () -> Unit,
) {
    val session = call.principal<UserSession>()
    if (session == null) {
        call.respondWithUnauthorized()
        return
    }
    context(session) { block() }
}

class UserSessionAuthenticationProvider(config: Config) :
    AuthenticationProvider(config) {
    private val authRoutes = config.authRoutes

    class Config(name: String?, val authRoutes: AuthRoutes) :
        AuthenticationProvider.Config(name)

    override suspend fun onAuthenticate(context: AuthenticationContext) {
        val call = context.call
        val token = authRoutes.extractAccessToken(call)
        if (token == null) {
            context.challenge(
                AUTH_SESSION,
                AuthenticationFailedCause.NoCredentials,
            ) { challenge, _ ->
                call.respondWithUnauthorized()
                challenge.complete()
            }
            return
        }

        when (val result = authRoutes.authorize(token)) {
            is arrow.core.Either.Left ->
                context.challenge(
                    AUTH_SESSION,
                    AuthenticationFailedCause.InvalidCredentials,
                ) { challenge, _ ->
                    call.respondApiError(result.value)
                    challenge.complete()
                }
            is arrow.core.Either.Right -> {
                call.putUserSession(result.value)
                context.principal(result.value)
            }
        }
    }
}

class AuthRoutes(
    private val authService: AuthService,
    private val documentsDb: DocumentsDatabase?,
) {
    suspend fun authorize(token: String) = authService.authorize(token)

    suspend fun loginRoute(call: ApplicationCall) {
        val request = receiveLoginRequest(call)
        if (request == null) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(error = "Invalid request"),
            )
            return
        }

        val login =
            authService.login(request.username, request.password).getOrElse {
                error ->
                call.respondApiError(error)
                return
            }
        if (login == null) {
            call.respond(
                HttpStatusCode.Unauthorized,
                ErrorResponse("Invalid credentials"),
            )
            return
        }

        val configuredServices =
            context(login.session) { computeConfiguredServices() }
        call.respond(
            LoginResponse(
                token = login.rawToken,
                configuredServices = configuredServices,
            )
        )
    }

    private suspend fun receiveLoginRequest(
        call: ApplicationCall
    ): LoginRequest? =
        when (call.request.contentType()) {
            ContentType.Application.Json -> {
                runCatching { call.receive<LoginRequest>() }.getOrNull()
            }
            ContentType.Application.FormUrlEncoded -> {
                val params =
                    runCatching { call.receiveParameters() }.getOrNull()
                val username = params?.get("username")
                val password = params?.get("password")
                if (username != null && password != null) {
                    LoginRequest(username = username, password = password)
                } else {
                    null
                }
            }
            else -> {
                logger.warn(
                    "Unsupported content type: ${call.request.contentType()}"
                )
                null
            }
        }

    context(session: UserSession)
    suspend fun protectedRoute(call: ApplicationCall) {
        val configuredServices = computeConfiguredServices()
        call.respond(
            UserResponse(
                username = session.user.username,
                configuredServices = configuredServices,
                userUuid = session.user.uuid.toString(),
            )
        )
    }

    suspend fun logoutRoute(token: String, call: ApplicationCall) {
        val _ =
            authService.logout(token).getOrElse { error ->
                call.respondApiError(error)
                return
            }
        call.respond(mapOf("success" to true))
    }

    context(session: UserSession)
    private suspend fun computeConfiguredServices(): ConfiguredServices {
        val userUuid = session.user.uuid
        val settings = session.user.settings

        val twitterTokenKey = "twitter-oauth-token:$userUuid"
        val linkedInTokenKey = "linkedin-oauth-token:$userUuid"
        val twitterOk =
            settings?.twitter != null &&
                documentsDb?.searchByKey(twitterTokenKey)?.getOrElse { null } !=
                    null
        val linkedInOk =
            settings?.linkedin != null &&
                documentsDb?.searchByKey(linkedInTokenKey)?.getOrElse {
                    null
                } != null

        return ConfiguredServices(
            mastodon = settings?.mastodon != null,
            bluesky = settings?.bluesky != null,
            twitter = twitterOk,
            linkedin = linkedInOk,
            llm = settings?.llm != null,
        )
    }

    fun extractAccessToken(call: ApplicationCall): String? {
        val authHeader = call.request.headers[HttpHeaders.Authorization]
        if (!authHeader.isNullOrBlank()) {
            val parts = authHeader.trim().split(" ")
            if (
                parts.size == 2 && parts[0].equals("Bearer", ignoreCase = true)
            ) {
                return parts[1]
            } else {
                logger.warn("Malformed Authorization header: $authHeader")
            }
        }
        call.request.cookies["access_token"]?.let {
            return it
        }
        return null
    }
}

fun Application.configureAuth(authRoutes: AuthRoutes) {
    install(Authentication) {
        register(
            UserSessionAuthenticationProvider(
                UserSessionAuthenticationProvider.Config(
                    AUTH_SESSION,
                    authRoutes,
                )
            )
        )
    }
}

private val logger by loggerFactory()
