package socialpublish.backend.clients.twitter

import arrow.core.Either
import arrow.core.raise.context.either
import arrow.core.raise.context.raise
import kotlinx.serialization.Serializable
import socialpublish.backend.common.jsonCommon

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
) {
    companion object {
        private val json = jsonCommon

        /**
         * Parse a persisted Twitter OAuth document.
         *
         * New records use this wrapper shape so the OAuth 1.0a request-token
         * secret can be preserved between authorize and callback. Older records
         * contain a bare [TwitterOAuthToken]; those are still accepted and
         * wrapped as [accessToken] to preserve DB compatibility.
         */
        fun parse(
            payload: String
        ): Either<IllegalArgumentException, TwitterOAuthDocument> = either {
            try {
                val ref = json.decodeFromString<TwitterOAuthDocument>(payload)
                if (ref.accessToken == null && ref.pendingRequest == null)
                    throw IllegalArgumentException(
                        "Both fields missing from payload"
                    )
                ref
            } catch (e: IllegalArgumentException) {
                try {
                    val t = json.decodeFromString<TwitterOAuthToken>(payload)
                    TwitterOAuthDocument(accessToken = t)
                } catch (e2: IllegalArgumentException) {
                    e.addSuppressed(e2)
                    raise(
                        IllegalArgumentException(
                            "Invalid TwitterOAuthDocument",
                            e,
                        )
                    )
                }
            }
        }
    }

    fun toJson(): String = json.encodeToString(this)
}

@Serializable data class TwitterMediaResponse(val media_id_string: String)

@Serializable data class TwitterPostResponse(val data: TwitterPostData)

@Serializable data class TwitterPostData(val id: String, val text: String)

@Serializable
data class TwitterCreateRequest(
    val text: String,
    val media: TwitterMedia? = null,
)

@Serializable data class TwitterMedia(val media_ids: List<String>)
