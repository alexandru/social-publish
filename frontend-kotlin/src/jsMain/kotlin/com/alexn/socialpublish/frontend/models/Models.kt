package com.alexn.socialpublish.frontend.models

import web.file.File

data class SelectedImage(
    val id: Int,
    val file: File? = null,
    val altText: String? = null,
)

enum class Target(val apiValue: String) {
    MASTODON("mastodon"),
    TWITTER("twitter"),
    BLUESKY("bluesky"),
    LINKEDIN("linkedin"),
}

data class PublishFormData(
    val content: String? = null,
    val link: String? = null,
    val targets: List<Target> = emptyList(),
    val rss: String? = null,
    val cleanupHtml: Boolean = false,
)

enum class MessageType {
    INFO,
    WARNING,
    ERROR,
}
