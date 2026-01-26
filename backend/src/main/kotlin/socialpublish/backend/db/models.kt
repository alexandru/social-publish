package socialpublish.backend.db

import java.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.serialization.Serializable

/**
 * Custom exception class for database-related errors, because we want typed exceptions in our
 * lives.
 */
class DBException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Sealed class for SQL update exceptions with constraint violation details. */
sealed class SqlUpdateException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {
    data class UniqueViolation(
        val table: String?,
        val column: String?,
        val constraint: String?,
        override val cause: Throwable,
    ) : SqlUpdateException("Unique constraint violation: $constraint", cause)

    data class ForeignKeyViolation(
        val table: String?,
        val column: String?,
        val constraint: String?,
        override val cause: Throwable,
    ) : SqlUpdateException("Foreign key constraint violation: $constraint", cause)

    data class CheckViolation(
        val table: String?,
        val column: String?,
        val constraint: String?,
        override val cause: Throwable,
    ) : SqlUpdateException("Check constraint violation: $constraint", cause)

    data class Unknown(override val message: String, override val cause: Throwable) :
        SqlUpdateException(message, cause)
}

@Serializable
data class Tag(
    val name: String,
    // "target" or "label"
    val kind: String,
)

data class Document(
    val uuid: String,
    val searchKey: String,
    val kind: String,
    val tags: List<Tag>,
    val payload: String,
    val createdAt: Instant,
)

@Serializable
data class PostPayload(
    val content: String,
    val link: String? = null,
    val tags: List<String>? = null,
    val language: String? = null,
    val images: List<String>? = null,
)

@Serializable
data class Post(
    val uuid: String,
    val targets: List<String>,
    @Serializable(with = InstantSerializer::class) val createdAt: Instant,
    val content: String,
    val link: String? = null,
    val tags: List<String>? = null,
    val language: String? = null,
    val images: List<String>? = null,
)

/** User account for authentication. */
@OptIn(ExperimentalUuidApi::class)
data class User(
    val uuid: Uuid,
    val username: String,
    val passwordHash: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** Result of creating a user. */
sealed interface CreateUserResult {
    /** User was created successfully. */
    data class Created(val user: User) : CreateUserResult

    /** User with this username already exists. */
    data class DuplicateUsername(val username: String) : CreateUserResult
}

/** User session for JWT authentication with optional refresh token support. */
@OptIn(ExperimentalUuidApi::class)
data class UserSession(
    val uuid: Uuid,
    val userUuid: Uuid,
    val tokenHash: String,
    val refreshTokenHash: String?,
    val expiresAt: Instant,
    val createdAt: Instant,
)

/** Result of creating a user session. */
sealed interface CreateSessionResult {
    /** Session was created successfully. */
    data class Created(val session: UserSession) : CreateSessionResult

    /** Session with this token hash already exists. */
    data object DuplicateToken : CreateSessionResult
}
