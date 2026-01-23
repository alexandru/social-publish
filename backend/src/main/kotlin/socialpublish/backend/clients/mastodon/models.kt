package socialpublish.backend.clients.mastodon

import kotlinx.serialization.Serializable

@Serializable
data class MastodonMediaResponse(
    val id: String,
    val url: String? = null,
    val preview_url: String? = null,
    val description: String? = null,
)

@Serializable data class MastodonStatusResponse(val id: String, val uri: String, val url: String)
