package socialpublish.backend.clients.twitter

import kotlinx.serialization.Serializable

@Serializable data class TwitterOAuthToken(val key: String, val secret: String)

/**
 * Represents a pending OAuth 1.0a request token during the authorize step,
 * including the token secret required for the access-token exchange.
 */
@Serializable
data class TwitterOAuthRequestToken(val token: String, val secret: String)

/**
 * Backward-compatible wrapper for the `twitter-oauth-token` document payload.
 *
 * Existing documents contain bare {@link TwitterOAuthToken} JSON. This wrapper
 * adds an optional [pendingRequest] field for the request-token secret, while
 * keeping the final [accessToken] in the same document.
 */
@Serializable
data class TwitterOAuthDocument(
    val accessToken: TwitterOAuthToken? = null,
    val pendingRequest: TwitterOAuthRequestToken? = null,
)

@Serializable data class TwitterMediaResponse(val media_id_string: String)

@Serializable data class TwitterPostResponse(val data: TwitterPostData)

@Serializable data class TwitterPostData(val id: String, val text: String)

@Serializable
data class TwitterCreateRequest(
    val text: String,
    val media: TwitterMedia? = null,
)

@Serializable data class TwitterMedia(val media_ids: List<String>)
