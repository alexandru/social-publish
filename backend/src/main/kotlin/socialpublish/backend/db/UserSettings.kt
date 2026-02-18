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
) {
    fun toConfig() = BlueskyConfig(service = service, username = username, password = password)
}

@Serializable
data class MastodonUserSettings(val host: String, val accessToken: String) {
    fun toConfig() = MastodonConfig(host = host, accessToken = accessToken)
}

@Serializable
data class TwitterUserSettings(val oauth1ConsumerKey: String, val oauth1ConsumerSecret: String) {
    fun toConfig() =
        TwitterConfig(
            oauth1ConsumerKey = oauth1ConsumerKey,
            oauth1ConsumerSecret = oauth1ConsumerSecret,
        )
}

@Serializable
data class LinkedInUserSettings(val clientId: String, val clientSecret: String) {
    fun toConfig() = LinkedInConfig(clientId = clientId, clientSecret = clientSecret)
}

@Serializable
data class LlmUserSettings(val apiUrl: String, val apiKey: String, val model: String) {
    fun toConfig() = LlmConfig(apiUrl = apiUrl, apiKey = apiKey, model = model)
}
