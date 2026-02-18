package socialpublish.backend.clients.bluesky

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable data class BlueskyConfig(val service: String, val username: String, val password: String)

@Serializable
data class BlueskySessionResponse(
    val accessJwt: String,
    val refreshJwt: String,
    val handle: String,
    val did: String,
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
