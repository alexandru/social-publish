package socialpublish.backend.db

import java.time.Instant
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
