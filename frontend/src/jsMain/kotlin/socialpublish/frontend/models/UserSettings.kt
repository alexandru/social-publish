package socialpublish.frontend.models

import kotlinx.serialization.Serializable

/**
 * All per-user social network and integration settings, matching the backend UserSettings.
 *
 * The empty-string defaults in nested classes are used for form initialization and local UI state.
 * The AccountPage validates that required fields are non-blank before constructing and saving these
 * objects, so the backend only receives non-empty, meaningful values.
 */
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
    val username: String = "",
    val password: String = "",
)

@Serializable data class MastodonUserSettings(val host: String = "", val accessToken: String = "")

@Serializable
data class TwitterUserSettings(
    val oauth1ConsumerKey: String = "",
    val oauth1ConsumerSecret: String = "",
)

@Serializable
data class LinkedInUserSettings(val clientId: String = "", val clientSecret: String = "")

@Serializable
data class LlmUserSettings(val apiUrl: String = "", val apiKey: String = "", val model: String = "")
