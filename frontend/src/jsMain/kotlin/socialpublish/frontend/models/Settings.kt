package socialpublish.frontend.models

import kotlinx.serialization.Serializable

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

@Serializable data class UserSettingsResponse(val settings: UserSettings? = null)
