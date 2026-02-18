package socialpublish.backend.server.routes

import arrow.core.getOrElse
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.request.contentType
import io.ktor.server.request.receive
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import java.util.UUID
import kotlinx.serialization.Serializable
import socialpublish.backend.common.ErrorResponse
import socialpublish.backend.db.UsersDatabase
import socialpublish.backend.modules.AuthModule
import socialpublish.backend.server.ServerAuthConfig

private val logger = KotlinLogging.logger {}

@Serializable data class LoginRequest(val username: String, val password: String)

@Serializable data class AuthStatus(val twitter: Boolean = false, val linkedin: Boolean = false)

/** Which social network services are configured for the authenticated user. */
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
    val hasAuth: AuthStatus,
    val configuredServices: ConfiguredServices,
)

@Serializable data class UserResponse(val username: String)

class AuthRoutes(
    private val config: ServerAuthConfig,
    private val usersDb: UsersDatabase,
    private val twitterAuthProvider: (suspend () -> Boolean)? = null,
    private val linkedInAuthProvider: (suspend () -> Boolean)? = null,
) {
    val authModule = AuthModule(config.jwtSecret)

    suspend fun receiveLoginRequest(call: ApplicationCall): LoginRequest? =
        when (call.request.contentType()) {
            ContentType.Application.Json -> {
                runCatching { call.receive<LoginRequest>() }.getOrNull()
            }
            ContentType.Application.FormUrlEncoded -> {
                val params = runCatching { call.receiveParameters() }.getOrNull()
                val username = params?.get("username")
                val password = params?.get("password")
                if (username != null && password != null) {
                    LoginRequest(username = username, password = password)
                } else {
                    null
                }
            }
            else -> {
                logger.warn { "Unsupported content type: ${call.request.contentType()}" }
                null
            }
        }

    suspend fun loginRoute(call: ApplicationCall) {
        val request = receiveLoginRequest(call)
        if (request == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "Invalid credentials"))
            return
        }

        val user =
            usersDb.findByUsername(request.username).getOrElse {
                logger.error(it) { "DB error during login for ${request.username}" }
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse(error = "Server error"),
                )
                return
            }

        val isPasswordValid =
            if (user != null) {
                try {
                    authModule.verifyPassword(request.password, user.passwordHash)
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to verify password for user: ${request.username}" }
                    false
                }
            } else {
                false
            }

        if (user != null && isPasswordValid) {
            val token = authModule.generateToken(user.username, user.uuid)
            val hasTwitterAuth = twitterAuthProvider?.invoke() ?: false
            val hasLinkedInAuth = linkedInAuthProvider?.invoke() ?: false
            val settings = user.settings
            val configuredServices =
                ConfiguredServices(
                    mastodon = settings?.mastodon != null,
                    bluesky = settings?.bluesky != null,
                    twitter = settings?.twitter != null,
                    linkedin = settings?.linkedin != null,
                    llm = settings?.llm != null,
                )
            call.respond(
                LoginResponse(
                    token = token,
                    hasAuth = AuthStatus(twitter = hasTwitterAuth, linkedin = hasLinkedInAuth),
                    configuredServices = configuredServices,
                )
            )
        } else {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid credentials"))
        }
    }

    suspend fun protectedRoute(call: ApplicationCall) {
        val principal = call.principal<JWTPrincipal>()
        val username = principal?.getClaim("username", String::class)

        if (username != null) {
            call.respond(UserResponse(username = username))
        } else {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse(error = "Unauthorized"))
        }
    }

    /** Extract JWT token from the request (Authorization header, query param, or cookie). */
    fun extractJwtToken(call: ApplicationCall): String? {
        val authHeader = call.request.headers[HttpHeaders.Authorization]
        if (!authHeader.isNullOrBlank()) {
            val parts = authHeader.trim().split(" ")
            if (parts.size == 2 && parts[0].equals("Bearer", ignoreCase = true)) {
                return parts[1]
            } else {
                logger.warn { "Malformed Authorization header: $authHeader" }
            }
        }

        call.request.queryParameters["access_token"]?.let {
            return it
        }
        call.request.cookies["access_token"]?.let {
            return it
        }
        return null
    }

    /**
     * Extract the authenticated user's UUID from the request JWT. Returns null and responds with
     * 401 if the token is missing or invalid.
     */
    suspend fun extractUserUuidOrRespond(call: ApplicationCall): UUID? {
        val token =
            extractJwtToken(call)
                ?: run {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse(error = "Unauthorized"))
                    return null
                }
        return authModule.getUserUuidFromToken(token)
            ?: run {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse(error = "Unauthorized"))
                null
            }
    }

    fun configureAuth(app: Application) {
        app.install(Authentication) {
            jwt("auth-jwt") {
                realm = "social-publish"
                authHeader { call ->
                    extractJwtToken(call)?.let { token -> HttpAuthHeader.Single("Bearer", token) }
                }
                verifier(authModule.verifier)
                validate { credential ->
                    val username = credential.payload.getClaim("username").asString()
                    if (username != null) {
                        JWTPrincipal(credential.payload)
                    } else {
                        null
                    }
                }
                challenge { _, _ ->
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse(error = "Unauthorized"))
                }
            }
        }
    }
}

fun Application.configureAuth(authRoutes: AuthRoutes) {
    authRoutes.configureAuth(this)
}
