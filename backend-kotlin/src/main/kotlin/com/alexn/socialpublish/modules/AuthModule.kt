package com.alexn.socialpublish.modules

import com.alexn.socialpublish.config.AppConfig
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import kotlinx.serialization.Serializable
import java.util.*

private val logger = KotlinLogging.logger {}

@Serializable
data class LoginRequest(
    val username: String,
    val password: String
)

@Serializable
data class LoginResponse(
    val token: String,
    val hasAuth: AuthStatus
)

@Serializable
data class AuthStatus(
    val twitter: Boolean = false
)

@Serializable
data class UserResponse(
    val username: String
)

class AuthModule(private val config: AppConfig) {
    
    private val algorithm = Algorithm.HMAC256(config.serverAuthJwtSecret)
    
    /**
     * Generate JWT token for authenticated user
     */
    fun generateToken(username: String): String {
        return JWT.create()
            .withSubject(username)
            .withClaim("username", username)
            .withExpiresAt(Date(System.currentTimeMillis() + 168 * 60 * 60 * 1000)) // 168 hours = 7 days
            .sign(algorithm)
    }
    
    /**
     * Verify JWT token
     */
    fun verifyToken(token: String): String? {
        return try {
            val verifier = JWT.require(algorithm).build()
            val jwt = verifier.verify(token)
            jwt.getClaim("username").asString()
        } catch (e: Exception) {
            logger.warn(e) { "Failed to verify JWT token" }
            null
        }
    }
    
    /**
     * Login route handler
     */
    suspend fun login(call: ApplicationCall) {
        val request = call.receiveParameters()
        val username = request["username"]
        val password = request["password"]
        
        if (username == config.serverAuthUsername && password == config.serverAuthPassword) {
            val token = generateToken(username)
            call.respond(
                LoginResponse(
                    token = token,
                    hasAuth = AuthStatus(twitter = false) // TODO: Check Twitter auth status
                )
            )
        } else {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid credentials"))
        }
    }
    
    /**
     * Protected route handler
     */
    suspend fun protectedRoute(call: ApplicationCall) {
        val principal = call.principal<JWTPrincipal>()
        val username = principal?.getClaim("username", String::class)
        
        if (username != null) {
            call.respond(UserResponse(username = username))
        } else {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
        }
    }
}

/**
 * Configure JWT authentication for Ktor
 */
fun Application.configureAuth(config: AppConfig) {
    install(Authentication) {
        jwt("auth-jwt") {
            realm = "social-publish"
            verifier(
                JWT.require(Algorithm.HMAC256(config.serverAuthJwtSecret))
                    .build()
            )
            validate { credential ->
                val username = credential.payload.getClaim("username").asString()
                if (username != null) {
                    JWTPrincipal(credential.payload)
                } else {
                    null
                }
            }
            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
            }
        }
    }
}
