package socialpublish.backend.clients.bluesky

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class BlueskyConfig(val service: String, val username: String, val password: String)

@Serializable
data class BlueskySessionResponse(
    val accessJwt: String,
    val refreshJwt: String,
    val handle: String,
    val did: String,
)

@Serializable data class BlueskyCreateSessionRequest(val identifier: String, val password: String)

@Serializable data class BlueskyBlobUploadResponse(val blob: BlueskyBlobUploadBlob)

@Serializable
data class BlueskyBlobUploadBlob(
    val ref: JsonObject,
    @SerialName("\$type") val type: String? = null,
    val mimeType: String? = null,
    val size: Long? = null,
)

@Serializable
data class BlueskyBlobRef(
    val `$type`: String = "blob",
    val ref: JsonObject,
    val mimeType: String,
    val size: Long,
)

@Serializable
data class BlueskyImageEmbed(
    val alt: String,
    val image: BlueskyBlobRef,
    val aspectRatio: BlueskyAspectRatio? = null,
)

@Serializable data class BlueskyAspectRatio(val width: Int, val height: Int)

@Serializable data class BlueskyPostResponse(val uri: String, val cid: String)

@Serializable data class BlueskyFacetIndex(val byteStart: Int, val byteEnd: Int)

@Serializable
data class BlueskyFacetFeature(
    val `$type`: String,
    val uri: String? = null,
    val did: String? = null,
    val tag: String? = null,
)

@Serializable
data class BlueskyFacet(val index: BlueskyFacetIndex, val features: List<BlueskyFacetFeature>)

@Serializable data class BlueskyReplyRef(val uri: String, val cid: String)

@Serializable data class BlueskyReply(val root: BlueskyReplyRef, val parent: BlueskyReplyRef)

@Serializable
data class BlueskyExternal(
    val uri: String,
    val title: String,
    val description: String,
    val thumb: BlueskyBlobRef? = null,
)

@Serializable data class BlueskyExternalEmbed(val `$type`: String, val external: BlueskyExternal)

@Serializable
data class BlueskyImagesEmbed(val `$type`: String, val images: List<BlueskyImageEmbed>)

@Serializable
data class BlueskyPostRecord(
    val `$type`: String,
    val text: String,
    val createdAt: String,
    val facets: List<BlueskyFacet>? = null,
    val langs: List<String>? = null,
    val reply: BlueskyReply? = null,
    val embed: JsonElement? = null,
)

@Serializable
data class BlueskyCreateRecordRequest(
    val repo: String,
    val collection: String,
    val record: BlueskyPostRecord,
)
