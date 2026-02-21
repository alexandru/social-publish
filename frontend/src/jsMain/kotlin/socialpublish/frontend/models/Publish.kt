package socialpublish.frontend.models

import kotlinx.serialization.Serializable

@Serializable data class FileUploadResponse(val uuid: String)

@Serializable
data class PublishRequest(
    val targets: List<String>,
    val language: String? = null,
    val messages: List<PublishRequestMessage>,
)

@Serializable
data class PublishRequestMessage(
    val content: String,
    val link: String? = null,
    val images: List<String>? = null,
)

@Serializable
data class ModulePostResponse(
    val module: String,
    val uri: String? = null,
    val id: String? = null,
    val cid: String? = null,
    val postId: String? = null,
    val messages: List<ModulePostMessage>? = null,
)

@Serializable
data class ModulePostMessage(
    val id: String,
    val uri: String? = null,
    val replyToId: String? = null,
    val commentOnId: String? = null,
)

@Serializable
data class GenerateAltTextRequest(
    val imageUuid: String,
    val userContext: String? = null,
    val language: String? = null,
)

@Serializable data class GenerateAltTextResponse(val altText: String)
