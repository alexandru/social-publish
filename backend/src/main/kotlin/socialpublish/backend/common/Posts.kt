@file:UseSerializers(NonEmptyListSerializer::class)

package socialpublish.backend.common

import arrow.core.NonEmptyList
import arrow.core.nonEmptyListOf
import arrow.core.raise.either
import arrow.core.raise.ensureNotNull
import arrow.core.serialization.NonEmptyListSerializer
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
enum class Target {
    @SerialName("feed") Feed,
    @SerialName("bluesky") Bluesky,
    @SerialName("mastodon") Mastodon,
    @SerialName("twitter") Twitter,
    @SerialName("linkedin") LinkedIn;

    val serialName: String
        get() = name.lowercase()

    companion object {
        /**
         * Resolve a target from its lowercase serial name. Case-insensitive
         * match against the Kotlin enum name.
         */
        fun bySerialName(name: String): Target? = entries.find {
            it.name.equals(name, ignoreCase = true)
        }
    }
}

@Serializable
data class NewPostRequest(
    val targets: List<Target>? = null,
    val language: String? = null,
    val messages: NonEmptyList<NewPostRequestMessage>,
) {
    companion object {
        fun singleMessage(
            content: String,
            targets: List<Target>? = null,
            link: String? = null,
            language: String? = null,
            images: List<String>? = null,
        ): NewPostRequest =
            NewPostRequest(
                targets = targets,
                language = language,
                messages =
                    nonEmptyListOf(
                        NewPostRequestMessage(
                            content = content,
                            link = link,
                            images = images,
                        )
                    ),
            )

        fun singleMessageFromTargetNames(
            content: String,
            targets: List<String>?,
            link: String? = null,
            language: String? = null,
            images: List<String>? = null,
        ) = either {
            val parsedTargets = targets?.map { target ->
                ensureNotNull(Target.bySerialName(target)) {
                    ErrorResponse(error = "Unknown target: $target")
                }
            }
            singleMessage(
                content = content,
                targets = parsedTargets,
                link = link,
                language = language,
                images = images,
            )
        }
    }
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

@Serializable(with = NewPostResponseSerializer::class)
sealed class NewPostResponse {
    abstract val module: String
}

object NewPostResponseSerializer :
    JsonContentPolymorphicSerializer<NewPostResponse>(NewPostResponse::class) {
    override fun selectDeserializer(
        element: JsonElement
    ): DeserializationStrategy<NewPostResponse> = let {
        val module =
            element.jsonObject["module"]?.jsonPrimitive?.content?.let {
                Target.bySerialName(it)
            }
        when (module) {
            Target.Bluesky -> NewBlueSkyPostResponse.serializer()
            Target.Mastodon -> NewMastodonPostResponse.serializer()
            Target.Feed -> NewFeedPostResponse.serializer()
            Target.Twitter -> NewTwitterPostResponse.serializer()
            Target.LinkedIn -> NewLinkedInPostResponse.serializer()
            null -> throw SerializationException("Unknown post response module")
        }
    }
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
