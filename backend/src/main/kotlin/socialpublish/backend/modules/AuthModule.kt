package socialpublish.backend.modules

import at.favre.lib.crypto.bcrypt.BCrypt as FavreBCrypt
import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.Date

private val logger = KotlinLogging.logger {}

class AuthModule(jwtSecret: String) {
    private val algorithm = Algorithm.HMAC256(jwtSecret)

    val verifier: JWTVerifier by lazy { JWT.require(algorithm).build() }

    /** Generate JWT token for authenticated user */
    fun generateToken(username: String, userUuid: String): String {
        return JWT.create()
            .withSubject(username)
            .withClaim("username", username)
            .withClaim("user_uuid", userUuid)
            .withExpiresAt(Date(System.currentTimeMillis() + JWT_EXPIRATION_MILLIS))
            .sign(algorithm)
    }

    /** Verify JWT token and return username */
    fun verifyToken(token: String): String? {
        return try {
            val jwt = verifier.verify(token)
            jwt.getClaim("username").asString()
        } catch (e: Exception) {
            logger.warn(e) { "Failed to verify JWT token" }
            null
        }
    }

    /** Extract user UUID from verified JWT token */
    fun getUserUuid(token: String): String? {
        return try {
            val jwt = verifier.verify(token)
            jwt.getClaim("user_uuid").asString()
        } catch (e: Exception) {
            logger.warn(e) { "Failed to extract user UUID from JWT token" }
            null
        }
    }

    /**
     * Verify a password against a BCrypt hash.
     *
     * The stored password must be a BCrypt hash (starts with $2a$, $2b$, or $2y$).
     */
    fun verifyPassword(providedPassword: String, storedPassword: String): Boolean {
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
