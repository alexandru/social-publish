package socialpublish.backend.clients.linkpreview

import kotlinx.serialization.Serializable

/**
 * Represents a link preview extracted from a web page's metadata.
 *
 * @property title The title of the link (from og:title, twitter:title, or <title>)
 * @property description The description of the link (from og:description, twitter:description, or
 *   meta description)
 * @property url The canonical URL of the link (from og:url, twitter:url, or fallback URL)
 * @property image The image URL for the preview (from og:image, twitter:image, optional)
 */
@Serializable
data class LinkPreview(
    val title: String,
    val description: String?,
    val url: String,
    val image: String?,
)
