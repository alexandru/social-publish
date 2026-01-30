package socialpublish.backend.common

import kotlinx.serialization.Serializable

@Serializable
data class NewPostRequest(
    val content: String,
    val targets: List<String>? = null,
    val link: String? = null,
    val language: String? = null,
    val cleanupHtml: Boolean? = null,
    val images: List<String>? = null,
) {
    fun validate(): ValidationError? {
        if (content.isEmpty() || content.length > 1000) {
            return ValidationError(
                status = 400,
                errorMessage = "Content must be between 1 and 1000 characters",
            )
        }
        return null
    }
}

@Serializable
sealed class NewPostResponse {
    abstract val module: String
}

@Serializable
data class NewBlueSkyPostResponse(val uri: String, val cid: String? = null) : NewPostResponse() {
    override val module: String = "bluesky"
}

@Serializable
data class NewMastodonPostResponse(val uri: String) : NewPostResponse() {
    override val module: String = "mastodon"
}

@Serializable
data class NewRssPostResponse(val uri: String) : NewPostResponse() {
    override val module: String = "rss"
}

@Serializable
data class NewTwitterPostResponse(val id: String) : NewPostResponse() {
    override val module: String = "twitter"
}

@Serializable
data class NewLinkedInPostResponse(val postId: String) : NewPostResponse() {
    override val module: String = "linkedin"
}

@Serializable
data class NewMetaThreadsPostResponse(val id: String) : NewPostResponse() {
    override val module: String = "metathreads"
}
