package socialpublish.backend.db

import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Parse and return user settings, or null if not set or invalid */
    fun getSettings(): UserSettings? {
        return settings?.let {
            try {
                json.decodeFromString<UserSettings>(it)
            } catch (e: Exception) {
                null
            }
        }
    }
}

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

/** Context for Bluesky API calls with user-specific settings */
data class UserBlueskyContext(val userUuid: UUID, val settings: BlueskyUserSettings)

/** Context for Mastodon API calls with user-specific settings */
data class UserMastodonContext(val userUuid: UUID, val settings: MastodonUserSettings)

/** Context for Twitter API calls with user-specific settings */
data class UserTwitterContext(val userUuid: UUID, val settings: TwitterUserSettings)

/** Context for LinkedIn API calls with user-specific settings */
data class UserLinkedInContext(val userUuid: UUID, val settings: LinkedInUserSettings)

/** Context for LLM API calls with user-specific settings */
data class UserLlmContext(val userUuid: UUID, val settings: LlmUserSettings)

/** Helper extensions for UserSettings to create context objects */
data class UserSettingsWithUuid(val userUuid: UUID, val settings: UserSettings) {
    val blueskyContext: UserBlueskyContext? =
        settings.bluesky?.let { UserBlueskyContext(userUuid, it) }
    val mastodonContext: UserMastodonContext? =
        settings.mastodon?.let { UserMastodonContext(userUuid, it) }
    val twitterContext: UserTwitterContext? =
        settings.twitter?.let { UserTwitterContext(userUuid, it) }
    val linkedInContext: UserLinkedInContext? =
        settings.linkedin?.let { UserLinkedInContext(userUuid, it) }
    val llmContext: UserLlmContext? = settings.llm?.let { UserLlmContext(userUuid, it) }

    inline fun <R> withBlueskyContext(
        block:
            context(UserBlueskyContext)
            () -> R
    ): R? = blueskyContext?.let { ctx -> block(ctx) }

    inline fun <R> withMastodonContext(
        block:
            context(UserMastodonContext)
            () -> R
    ): R? = mastodonContext?.let { ctx -> block(ctx) }

    inline fun <R> withTwitterContext(
        block:
            context(UserTwitterContext)
            () -> R
    ): R? = twitterContext?.let { ctx -> block(ctx) }

    inline fun <R> withLinkedInContext(
        block:
            context(UserLinkedInContext)
            () -> R
    ): R? = linkedInContext?.let { ctx -> block(ctx) }

    inline fun <R> withLlmContext(
        block:
            context(UserLlmContext)
            () -> R
    ): R? = llmContext?.let { ctx -> block(ctx) }
}
