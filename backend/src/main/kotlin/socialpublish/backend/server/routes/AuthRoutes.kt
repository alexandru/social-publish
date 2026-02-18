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
import socialpublish.backend.db.DocumentsDatabase
import socialpublish.backend.db.UsersDatabase
import socialpublish.backend.modules.AuthModule
import socialpublish.backend.modules.VerifiedToken
import socialpublish.backend.server.ServerAuthConfig

private val logger = KotlinLogging.logger {}

@Serializable data class LoginRequest(val username: String, val password: String)

/**
 * Services configured and ready to use for the authenticated user.
 *
 * For Mastodon and Bluesky: true when credentials are stored.
 * For Twitter and LinkedIn: true when credentials are stored AND the OAuth flow has been completed
 * (i.e. the OAuth token is in the database).
 * LLM is a utility integration (alt-text generation), not a posting target.
 */
@Serializable
data class ConfiguredServices(
    val mastodon: Boolean = false,
    val bluesky: Boolean = false,
    val twitter: Boolean = false,
    val linkedin: Boolean = false,
    val llm: Boolean = false,
)

@Serializable data class LoginResponse(val token: String, val configuredServices: ConfiguredServices)

@Serializable data class UserResponse(val username: String)

class AuthRoutes(
    private val config: ServerAuthConfig,
    private val usersDb: UsersDatabase,
    private val documentsDb: DocumentsDatabase,
) {
    val authModule = AuthModule(config.jwtSecret)

    private suspend fun receiveLoginRequest(call: ApplicationCall): LoginRequest? =
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
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "Invalid request"))
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

        if (user == null || !isPasswordValid) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid credentials"))
            return
        }

        val token = authModule.generateToken(user.username, user.uuid)
        val settings = user.settings

        // twitter/linkedin are ready only when credentials AND OAuth token both exist
        val twitterTokenKey = "twitter-oauth-token:${user.uuid}"
        val linkedInTokenKey = "linkedin-oauth-token:${user.uuid}"
        val twitterOk =
            settings?.twitter != null &&
                documentsDb.searchByKey(twitterTokenKey).getOrElse { null } != null
        val linkedInOk =
            settings?.linkedin != null &&
                documentsDb.searchByKey(linkedInTokenKey).getOrElse { null } != null

        val configuredServices =
            ConfiguredServices(
                mastodon = settings?.mastodon != null,
                bluesky = settings?.bluesky != null,
                twitter = twitterOk,
                linkedin = linkedInOk,
                llm = settings?.llm != null,
            )
        call.respond(LoginResponse(token = token, configuredServices = configuredServices))
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

    /** Extract a raw JWT string from the request (Authorization header, query param, or cookie). */
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
        call.request.queryParameters["access_token"]?.let { return it }
        call.request.cookies["access_token"]?.let { return it }
        return null
    }

    /**
     * Verify the JWT in the request and return the verified token payload, or null if missing /
     * invalid.
     */
    fun verifyRequest(call: ApplicationCall): VerifiedToken? {
        val token = extractJwtToken(call) ?: return null
        return authModule.verifyTokenPayload(token)
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

/** Resolve the authenticated user's UUID from the Ktor JWT principal in the call. */
fun ApplicationCall.resolveUserUuid(): UUID? {
    val principal = principal<JWTPrincipal>() ?: return null
    return principal.getClaim("userUuid", String::class)?.let {
        runCatching { UUID.fromString(it) }.getOrNull()
    }
}
