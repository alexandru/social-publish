package socialpublish.backend.modules

import at.favre.lib.crypto.bcrypt.BCrypt as FavreBCrypt
import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.Date
import java.util.UUID

private val logger = KotlinLogging.logger {}

/** Verified JWT payload carrying the user's identity. */
data class VerifiedToken(val username: String, val userUuid: UUID)

class AuthModule(jwtSecret: String) {
    private val algorithm = Algorithm.HMAC256(jwtSecret)

    val verifier: JWTVerifier by lazy { JWT.require(algorithm).build() }

    /** Generate JWT token for authenticated user, carrying both username and userUuid. */
    fun generateToken(username: String, userUuid: UUID): String {
        return JWT.create()
            .withSubject(username)
            .withClaim("username", username)
            .withClaim("userUuid", userUuid.toString())
            .withExpiresAt(Date(System.currentTimeMillis() + JWT_EXPIRATION_MILLIS))
            .sign(algorithm)
    }

    /**
     * Verify a JWT token and return its payload, or null if the token is invalid/expired.
     *
     * The returned [VerifiedToken] carries both [VerifiedToken.username] and
     * [VerifiedToken.userUuid].
     */
    fun verifyTokenPayload(token: String): VerifiedToken? {
        return try {
            val jwt = verifier.verify(token)
            val username = jwt.getClaim("username").asString() ?: return null
            val userUuid =
                jwt.getClaim("userUuid").asString()?.let { UUID.fromString(it) } ?: return null
            VerifiedToken(username, userUuid)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to verify JWT token" }
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
        private const val JWT_EXPIRATION_MILLIS = 180L * 24 * 60 * 60 * 1000

        fun hashPassword(password: String, rounds: Int = 12): String {
            return String(
                FavreBCrypt.withDefaults().hash(rounds, password.toCharArray()),
                Charsets.UTF_8,
            )
        }
    }
}
