package socialpublish.backend.db

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import kotlinx.serialization.Serializable
import socialpublish.backend.clients.bluesky.BlueskyConfig
import socialpublish.backend.clients.linkedin.LinkedInConfig
import socialpublish.backend.clients.llm.LlmConfig
import socialpublish.backend.clients.mastodon.MastodonConfig
import socialpublish.backend.clients.twitter.TwitterConfig
import socialpublish.backend.common.jsonCommon

/**
 * All per-user social network and integration settings, stored as JSON in the
 * users' table.
 */
@Serializable
data class UserSettings(
    val bluesky: BlueskyConfig? = null,
    val mastodon: MastodonConfig? = null,
    val twitter: TwitterConfig? = null,
    val linkedin: LinkedInConfig? = null,
    val llm: LlmConfig? = null,
) {
    companion object {
        fun parse(
            raw: String
        ): Either<IllegalArgumentException, UserSettings?> =
            try {
                jsonCommon.decodeFromString<UserSettings>(raw).right()
            } catch (e: IllegalArgumentException) {
                e.left()
            }
    }
}
