package socialpublish.backend.modules

import at.favre.lib.crypto.bcrypt.BCrypt as FavreBCrypt
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.github.oshai.kotlinlogging.KotlinLogging
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
import io.ktor.server.request.receive
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import socialpublish.backend.models.ErrorResponse
import socialpublish.backend.server.ServerAuthConfig

private val logger = KotlinLogging.logger {}

@Serializable data class LoginRequest(val username: String, val password: String)

@Serializable data class LoginResponse(val token: String, val hasAuth: AuthStatus)

@Serializable data class AuthStatus(val twitter: Boolean = false, val linkedin: Boolean = false)

@Serializable data class UserResponse(val username: String)

/**
 * Simple in-memory rate limiter for login attempts. Tracks failed login attempts per IP address and
 * enforces a delay after too many failures.
 */
class LoginRateLimiter(
    private val maxAttempts: Int = 5,
    private val windowMillis: Long = 15 * 60 * 1000, // 15 minutes
) {
    private data class AttemptRecord(var count: Int, var firstAttemptTime: Long)

    private val attempts = ConcurrentHashMap<String, AttemptRecord>()

    /**
     * Check if a client IP is rate limited. Returns true if the client should be allowed, false if
     * rate limited.
     */
    fun checkLimit(clientIp: String): Boolean {
        val now = System.currentTimeMillis()
        val record = attempts.computeIfAbsent(clientIp) { AttemptRecord(0, now) }

        synchronized(record) {
            // Reset if window has passed
            if (now - record.firstAttemptTime > windowMillis) {
                record.count = 0
                record.firstAttemptTime = now
            }

            return record.count < maxAttempts
        }
    }

    /** Record a failed login attempt for a client IP. */
    fun recordFailure(clientIp: String) {
        val now = System.currentTimeMillis()
        val record = attempts.computeIfAbsent(clientIp) { AttemptRecord(0, now) }

        synchronized(record) {
            // Reset if window has passed
            if (now - record.firstAttemptTime > windowMillis) {
                record.count = 1
                record.firstAttemptTime = now
            } else {
                record.count++
            }
        }
    }

    /** Reset the attempt count for a client IP (e.g., after successful login). */
    fun reset(clientIp: String) {
        attempts.remove(clientIp)
    }
}

class AuthModule(
    private val config: ServerAuthConfig,
    private val twitterAuthProvider: (suspend () -> Boolean)? = null,
    private val linkedInAuthProvider: (suspend () -> Boolean)? = null,
) {
    private val algorithm = Algorithm.HMAC256(config.jwtSecret)
    private val rateLimiter = LoginRateLimiter()

    /** Generate JWT token for authenticated user */
    fun generateToken(username: String): String {
        return JWT.create()
            .withSubject(username)
            .withClaim("username", username)
            .withExpiresAt(Date(System.currentTimeMillis() + 24 * 60 * 60 * 1000)) // 24 hours
            .sign(algorithm)
    }

    /** Verify JWT token */
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

    private suspend fun receiveLoginRequest(call: ApplicationCall): LoginRequest? {
        val jsonRequest = runCatching { call.receive<LoginRequest>() }.getOrNull()
        if (jsonRequest != null) {
            return jsonRequest
        }

        val params = runCatching { call.receiveParameters() }.getOrNull()
        val username = params?.get("username")
        val password = params?.get("password")
        return if (username != null && password != null) {
            LoginRequest(username = username, password = password)
        } else {
            null
        }
    }

    /** Login route handler */
    suspend fun login(call: ApplicationCall) {
        // Get client IP for rate limiting
        val clientIp = call.request.local.remoteHost

        // Check rate limit
        if (!rateLimiter.checkLimit(clientIp)) {
            logger.warn { "Rate limit exceeded for IP: $clientIp" }
            call.respond(
                HttpStatusCode.TooManyRequests,
                ErrorResponse(error = "Too many login attempts. Please try again later."),
            )
            return
        }

        val request = receiveLoginRequest(call)
        if (request == null) {
            rateLimiter.recordFailure(clientIp)
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "Invalid credentials"))
            return
        }

        // Check username and verify password with BCrypt
        if (
            request.username == config.username &&
                verifyPassword(request.password, config.passwordHash)
        ) {
            // Successful login - reset rate limiter for this IP
            rateLimiter.reset(clientIp)
            val token = generateToken(request.username)
            val hasTwitterAuth = twitterAuthProvider?.invoke() ?: false
            val hasLinkedInAuth = linkedInAuthProvider?.invoke() ?: false
            call.respond(
                LoginResponse(
                    token = token,
                    hasAuth = AuthStatus(twitter = hasTwitterAuth, linkedin = hasLinkedInAuth),
                )
            )
        } else {
            // Failed login - record attempt
            rateLimiter.recordFailure(clientIp)
            logger.warn {
                "Failed login attempt for username: ${request.username} from IP: $clientIp"
            }
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid credentials"))
        }
    }

    /**
     * Verify a password against a BCrypt hash.
     *
     * The stored password must be a BCrypt hash (starts with $2a$, $2b$, or $2y$).
     */
    private fun verifyPassword(providedPassword: String, storedPassword: String): Boolean {
        val trimmedStoredPassword = storedPassword.trim()
        return try {
            val result =
                FavreBCrypt.verifyer()
                    .verify(providedPassword.toCharArray(), trimmedStoredPassword.toCharArray())
            result.verified
        } catch (e: Exception) {
            logger.warn(e) { "Failed to verify BCrypt password" }
            false
        }
    }

    /** Protected route handler */
    suspend fun protectedRoute(call: ApplicationCall) {
        val principal = call.principal<JWTPrincipal>()
        val username = principal?.getClaim("username", String::class)

        if (username != null) {
            call.respond(UserResponse(username = username))
        } else {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse(error = "Unauthorized"))
        }
    }

    companion object {
        /**
         * Generate a BCrypt hash for a password. Use this to create hashed passwords for
         * SERVER_AUTH_PASSWORD.
         *
         * Example usage:
         * ```
         * val hash = AuthModule.hashPassword("mypassword")
         * println("Use this as SERVER_AUTH_PASSWORD: $hash")
         * ```
         */
        fun hashPassword(password: String, rounds: Int = 12): String {
            return String(
                FavreBCrypt.withDefaults().hash(rounds, password.toCharArray()),
                Charsets.UTF_8,
            )
        }
    }
}

fun extractJwtToken(call: ApplicationCall): String? {
    val authHeader = call.request.headers[HttpHeaders.Authorization]
    if (!authHeader.isNullOrBlank()) {
        val parts = authHeader.trim().split(" ")
        if (parts.size == 2 && parts[0].equals("Bearer", ignoreCase = true)) {
            return parts[1]
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

/** Configure JWT authentication for Ktor */
fun Application.configureAuth(config: ServerAuthConfig) {
    install(Authentication) {
        jwt("auth-jwt") {
            realm = "social-publish"
            authHeader { call ->
                extractJwtToken(call)?.let { token -> HttpAuthHeader.Single("Bearer", token) }
            }
            verifier(JWT.require(Algorithm.HMAC256(config.jwtSecret)).build())
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
