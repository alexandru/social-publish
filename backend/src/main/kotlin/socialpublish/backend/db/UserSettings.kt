package socialpublish.backend.db

import kotlinx.serialization.Serializable
import socialpublish.backend.clients.bluesky.BlueskyConfig
import socialpublish.backend.clients.linkedin.LinkedInConfig
import socialpublish.backend.clients.llm.LlmConfig
import socialpublish.backend.clients.mastodon.MastodonConfig
import socialpublish.backend.clients.twitter.TwitterConfig

/** All per-user social network and integration settings, stored as JSON in the users table. */
@Serializable
data class UserSettings(
    val bluesky: BlueskyConfig? = null,
    val mastodon: MastodonConfig? = null,
    val twitter: TwitterConfig? = null,
    val linkedin: LinkedInConfig? = null,
    val llm: LlmConfig? = null,
)
