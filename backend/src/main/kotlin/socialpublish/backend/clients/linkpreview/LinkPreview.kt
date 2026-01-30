package socialpublish.backend.clients.linkpreview

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Represents a link preview extracted from a web page's metadata.
 *
 * @property title The title of the link (from og:title, twitter:title, or <title>)
 * @property description The description of the link (from og:description, twitter:description, or
 *   meta description)
 * @property url The canonical URL of the link (from og:url, twitter:url, or fallback URL)
 * @property imageUrl The image URL for the preview (from og:image, twitter:image, optional)
 * @property optimizedImageFile The optimized image file (temporary, managed by Resource)
 * @property optimizedImageMimeType The MIME type of the optimized image
 */
@Serializable
data class LinkPreview(
    val title: String,
    val description: String?,
    val url: String,
    val imageUrl: String?,
    @Transient val optimizedImageFile: File? = null,
    @Transient val optimizedImageMimeType: String? = null,
)
