package socialpublish.frontend.models

import kotlinx.serialization.Serializable

@Serializable data class FileUploadResponse(val uuid: String)

@Serializable
data class PublishRequest(
    val content: String,
    val link: String? = null,
    val targets: List<String>,
    val images: List<String> = emptyList(),
    val cleanupHtml: Boolean = false,
)

@Serializable
data class ModulePostResponse(
    val module: String,
    val uri: String? = null,
    val id: String? = null,
    val cid: String? = null,
)

@Serializable data class GenerateAltTextRequest(val imageUuid: String)

@Serializable data class GenerateAltTextResponse(val altText: String)

@Serializable data class UpdateAltTextRequest(val altText: String)

@Serializable data class UpdateAltTextResponse(val success: Boolean)
