package socialpublish.backend.modules

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import at.favre.lib.crypto.bcrypt.BCrypt as FavreBCrypt
import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.Date

private val logger = KotlinLogging.logger {}

sealed class AuthError(val message: String, open val cause: Throwable? = null) {
    data class InvalidToken(val details: String, override val cause: Throwable? = null) :
        AuthError("Invalid JWT token: $details", cause)

    data class InvalidCredentials(val details: String = "Invalid username or password") :
        AuthError(details)

    data class PasswordVerificationFailed(override val cause: Throwable) :
        AuthError("Password verification failed", cause)
}

class AuthModule(jwtSecret: String) {
    private val algorithm = Algorithm.HMAC256(jwtSecret)

    val verifier: JWTVerifier by lazy { JWT.require(algorithm).build() }

    /** Generate JWT token for authenticated user */
    fun generateToken(username: String): String {
        return JWT.create()
            .withSubject(username)
            .withClaim("username", username)
            .withExpiresAt(Date(System.currentTimeMillis() + JWT_EXPIRATION_MILLIS))
            .sign(algorithm)
    }

    /** Verify JWT token */
    fun verifyToken(token: String): Either<AuthError, String> {
        return try {
            val jwt = verifier.verify(token)
            val username = jwt.getClaim("username").asString()
            if (username.isNullOrBlank()) {
                AuthError.InvalidToken("Missing username claim").left()
            } else {
                username.right()
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to verify JWT token" }
            AuthError.InvalidToken("Token verification failed", e).left()
        }
    }

    /**
     * Verify a password against a BCrypt hash.
     *
     * The stored password must be a BCrypt hash (starts with $2a$, $2b$, or $2y$). Returns
     * Either.Right(Unit) on success, Either.Left(AuthError) on failure.
     */
    fun verifyPassword(providedPassword: String, storedPassword: String): Either<AuthError, Unit> {
        val trimmedStoredPassword = storedPassword.trim()
        return try {
            val result =
                FavreBCrypt.verifyer()
                    .verify(providedPassword.toCharArray(), trimmedStoredPassword.toCharArray())
            if (result.verified) {
                Unit.right()
            } else {
                AuthError.InvalidCredentials().left()
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to verify BCrypt password" }
            AuthError.PasswordVerificationFailed(e).left()
        }
    }

    companion object {
        /** JWT token expiration duration in milliseconds (6 months = ~180 days) */
        private const val JWT_EXPIRATION_MILLIS = 180L * 24 * 60 * 60 * 1000

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
