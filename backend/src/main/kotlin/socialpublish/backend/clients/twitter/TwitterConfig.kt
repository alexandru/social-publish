package socialpublish.backend.clients.twitter

import kotlinx.serialization.Serializable

@Serializable
data class TwitterConfig(
    val oauth1ConsumerKey: String,
    val oauth1ConsumerSecret: String,
    // Optional bases to allow testing against mocks
    val apiBase: String = "https://api.twitter.com",
    val uploadBase: String = "https://upload.twitter.com",
    // OAuth endpoints (allow overriding in tests)
    val oauthRequestTokenUrl: String =
        "https://api.twitter.com/oauth/request_token?x_auth_access_type=write",
    val oauthAccessTokenUrl: String = "https://api.twitter.com/oauth/access_token",
    val oauthAuthorizeUrl: String = "https://api.twitter.com/oauth/authorize",
)
