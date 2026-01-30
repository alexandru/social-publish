package socialpublish.frontend.models

import kotlinx.serialization.Serializable

@Serializable data class FileUploadResponse(val uuid: String)

@Serializable
data class PublishRequest(
    val content: String,
    val link: String? = null,
    val targets: List<String>,
    val images: List<String> = emptyList(),
    val language: String? = null,
)

@Serializable
data class ModulePostResponse(
    val module: String,
    val uri: String? = null,
    val id: String? = null,
    val cid: String? = null,
)

@Serializable
data class GenerateAltTextRequest(
    val imageUuid: String,
    val userContext: String? = null,
    val language: String? = null,
)

@Serializable data class GenerateAltTextResponse(val altText: String)
