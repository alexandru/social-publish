@file:UseSerializers(NonEmptyListSerializer::class)

package socialpublish.backend.common

import arrow.core.NonEmptyList
import arrow.core.nonEmptyListOf
import arrow.core.serialization.NonEmptyListSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

@Serializable
data class NewPostRequest(
    val targets: List<String>? = null,
    val language: String? = null,
    val messages: NonEmptyList<NewPostRequestMessage>,
) {
    constructor(
        content: String,
        targets: List<String>? = null,
        link: String? = null,
        language: String? = null,
        images: List<String>? = null,
    ) : this(
        targets = targets,
        language = language,
        messages =
            nonEmptyListOf(NewPostRequestMessage(content = content, link = link, images = images)),
    )
}

@Serializable
data class NewPostRequestMessage(
    val content: String,
    val link: String? = null,
    val images: List<String>? = null,
)

@Serializable
data class PublishedMessageResponse(
    val id: String,
    val uri: String? = null,
    val replyToId: String? = null,
    val commentOnId: String? = null,
)

@Serializable
sealed class NewPostResponse {
    abstract val module: String
}

@Serializable
data class NewBlueSkyPostResponse(
    val uri: String,
    val cid: String? = null,
    val messages: List<PublishedMessageResponse> = emptyList(),
) : NewPostResponse() {
    override val module: String = "bluesky"
}

@Serializable
data class NewMastodonPostResponse(
    val uri: String,
    val id: String = "",
    val messages: List<PublishedMessageResponse> = emptyList(),
) : NewPostResponse() {
    override val module: String = "mastodon"
}

@Serializable
data class NewFeedPostResponse(
    val uri: String,
    val messages: List<PublishedMessageResponse> = emptyList(),
) : NewPostResponse() {
    override val module: String = "feed"
}

@Serializable
data class NewTwitterPostResponse(
    val id: String,
    val messages: List<PublishedMessageResponse> = emptyList(),
) : NewPostResponse() {
    override val module: String = "twitter"
}

@Serializable
data class NewLinkedInPostResponse(
    val postId: String,
    val messages: List<PublishedMessageResponse> = emptyList(),
) : NewPostResponse() {
    override val module: String = "linkedin"
}
