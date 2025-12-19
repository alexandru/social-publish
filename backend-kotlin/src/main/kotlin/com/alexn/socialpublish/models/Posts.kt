package com.alexn.socialpublish.models

import kotlinx.serialization.Serializable

enum class Target {
    MASTODON,
    BLUESKY,
    TWITTER,
    LINKEDIN
}

@Serializable
data class NewPostRequest(
    val content: String,
    val targets: List<String>? = null,
    val link: String? = null,
    val language: String? = null,
    val cleanupHtml: Boolean? = null,
    val images: List<String>? = null
) {
    fun validate(): ValidationError? {
        if (content.isEmpty() || content.length > 1000) {
            return ValidationError(
                status = 400,
                errorMessage = "Content must be between 1 and 1000 characters"
            )
        }
        return null
    }
}

sealed class NewPostResponse {
    abstract val module: String
}

@Serializable
data class NewBlueSkyPostResponse(
    override val module: String = "bluesky",
    val uri: String,
    val cid: String? = null
) : NewPostResponse()

@Serializable
data class NewMastodonPostResponse(
    override val module: String = "mastodon",
    val uri: String
) : NewPostResponse()

@Serializable
data class NewRssPostResponse(
    override val module: String = "rss",
    val uri: String
) : NewPostResponse()

@Serializable
data class NewTwitterPostResponse(
    override val module: String = "twitter",
    val id: String
) : NewPostResponse()
