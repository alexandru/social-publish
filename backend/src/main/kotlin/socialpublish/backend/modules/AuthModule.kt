package socialpublish.backend.modules

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import at.favre.lib.crypto.bcrypt.BCrypt as FavreBCrypt
import io.github.oshai.kotlinlogging.KotlinLogging
import socialpublish.backend.common.ApiError
import socialpublish.backend.common.CaughtException
import socialpublish.backend.common.RequestError
import socialpublish.backend.common.rethrowIfFatalOrCancelled
import socialpublish.backend.db.UserSession
import socialpublish.backend.db.UserSessionsDatabase

private val logger = KotlinLogging.logger {}

class AuthService(private val userSessionsDb: UserSessionsDatabase) {
    suspend fun login(username: String, password: String) =
        userSessionsDb.login(username, password).mapLeft { dbError ->
            logger.error(dbError) { "DB error during login for $username" }
            CaughtException(status = 500, module = "auth", errorMessage = "Server error")
        }

    suspend fun logout(token: String): Either<ApiError, Boolean> =
        userSessionsDb.logout(token).mapLeft { dbError ->
            logger.error(dbError) { "DB error during logout" }
            CaughtException(status = 500, module = "auth", errorMessage = "Server error")
        }

    suspend fun authorize(token: String): Either<ApiError, UserSession> =
        when (val result = userSessionsDb.authorize(token)) {
            is Either.Left -> {
                logger.error(result.value) { "DB error during authorization" }
                CaughtException(status = 500, module = "auth", errorMessage = "Server error").left()
            }
            is Either.Right -> result.value?.right() ?: unauthorized().left()
        }

    companion object {
        fun unauthorized(): RequestError =
            RequestError(status = 401, module = "auth", errorMessage = "Unauthorized")
    }
}

object AuthModule {
    fun verifyPassword(providedPassword: String, storedPassword: String): Boolean {
        val trimmedStoredPassword = storedPassword.trim()
        return try {
            val result =
                FavreBCrypt.verifyer()
                    .verify(providedPassword.toCharArray(), trimmedStoredPassword.toCharArray())
            result.verified
        } catch (e: Throwable) {
            rethrowIfFatalOrCancelled(e)
            logger.warn(e) { "Failed to verify BCrypt password" }
            false
        }
    }

    fun hashPassword(password: String, rounds: Int = 12): String {
        return String(
            FavreBCrypt.withDefaults().hash(rounds, password.toCharArray()),
            Charsets.UTF_8,
        )
    }
}
