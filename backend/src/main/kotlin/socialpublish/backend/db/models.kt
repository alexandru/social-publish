package socialpublish.backend.db

import java.time.Instant
import java.util.UUID
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
data class User(
    val uuid: UUID,
    val username: String,
    val passwordHash: String,
    val settings: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** Generic result for create operations that may have duplicates. */
sealed interface CreateResult<out T> {
    /** Value was created successfully. */
    data class Created<out T>(val value: T) : CreateResult<T>

    /** Value already exists (duplicate). */
    data object Duplicate : CreateResult<Nothing>

    /** Convert to nullable, returning the value if Created, null if Duplicate. */
    val toNullable: T?
        get() =
            when (this) {
                is Created -> value
                is Duplicate -> null
            }
}

/** User session for JWT authentication with optional refresh token support. */
data class UserSession(
    val uuid: UUID,
    val userUuid: UUID,
    val tokenHash: String,
    val refreshTokenHash: String?,
    val expiresAt: Instant,
    val createdAt: Instant,
)

/** User settings stored as JSON in the database. */
@Serializable
data class UserSettings(
    val bluesky: BlueskyUserSettings? = null,
    val mastodon: MastodonUserSettings? = null,
    val twitter: TwitterUserSettings? = null,
    val linkedin: LinkedInUserSettings? = null,
    val llm: LlmUserSettings? = null,
)

@Serializable
data class BlueskyUserSettings(
    val service: String = "https://bsky.social",
    val username: String,
    val password: String,
)

@Serializable data class MastodonUserSettings(val host: String, val accessToken: String)

@Serializable
data class TwitterUserSettings(val oauth1ConsumerKey: String, val oauth1ConsumerSecret: String)

@Serializable data class LinkedInUserSettings(val clientId: String, val clientSecret: String)

@Serializable data class LlmUserSettings(val apiUrl: String, val apiKey: String, val model: String)
