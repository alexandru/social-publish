package socialpublish.backend.clients.twitter

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable data class TwitterOAuthToken(val key: String, val secret: String)

@Serializable
data class TwitterMediaResponse(@SerialName("media_id_string") val mediaIdString: String)

@Serializable data class TwitterPostResponse(val data: TwitterPostData)

@Serializable data class TwitterPostData(val id: String, val text: String)

@Serializable
data class TwitterCreateRequest(
    val text: String,
    val media: TwitterMedia? = null,
    val reply: TwitterReply? = null,
)

@Serializable data class TwitterMedia(@SerialName("media_ids") val mediaIds: List<String>)

@Serializable
data class TwitterReply(@SerialName("in_reply_to_tweet_id") val inReplyToTweetId: String)
