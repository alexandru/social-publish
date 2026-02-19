package socialpublish.backend.clients.twitter

import kotlinx.serialization.Serializable

@Serializable data class TwitterOAuthToken(val key: String, val secret: String)

@Serializable data class TwitterMediaResponse(val media_id_string: String)

@Serializable data class TwitterPostResponse(val data: TwitterPostData)

@Serializable data class TwitterPostData(val id: String, val text: String)

@Serializable data class TwitterCreateRequest(val text: String, val media: TwitterMedia? = null)

@Serializable data class TwitterMedia(val media_ids: List<String>)
