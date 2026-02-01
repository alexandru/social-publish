package socialpublish.backend.server.routes

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
import kotlinx.serialization.Serializable
import socialpublish.backend.common.ErrorResponse
import socialpublish.backend.modules.AuthModule
import socialpublish.backend.server.ServerAuthConfig

private val logger = KotlinLogging.logger {}

@Serializable data class LoginRequest(val username: String, val password: String)

@Serializable data class AuthStatus(val twitter: Boolean = false, val linkedin: Boolean = false)

@Serializable data class LoginResponse(val token: String, val hasAuth: AuthStatus)

@Serializable data class UserResponse(val username: String)

class AuthRoutes(
    private val config: ServerAuthConfig,
    private val twitterAuthProvider: (suspend () -> Boolean)? = null,
    private val linkedInAuthProvider: (suspend () -> Boolean)? = null,
) {
    private val authModule = AuthModule(config.jwtSecret)

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

        // Check username and verify password with BCrypt
        authModule
            .verifyPassword(request.password, config.passwordHash)
            .fold(
                ifLeft = { error ->
                    logger.warn {
                        "Failed to verify password for user ${request.username}: ${error.message}"
                    }
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid credentials"))
                },
                ifRight = { isPasswordValid ->
                    if (request.username == config.username && isPasswordValid) {
                        val token = authModule.generateToken(request.username)
                        val hasTwitterAuth = twitterAuthProvider?.invoke() ?: false
                        val hasLinkedInAuth = linkedInAuthProvider?.invoke() ?: false
                        call.respond(
                            LoginResponse(
                                token = token,
                                hasAuth =
                                    AuthStatus(twitter = hasTwitterAuth, linkedin = hasLinkedInAuth),
                            )
                        )
                    } else {
                        call.respond(
                            HttpStatusCode.Unauthorized,
                            ErrorResponse("Invalid credentials"),
                        )
                    }
                },
            )
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
