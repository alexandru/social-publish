package socialpublish.backend.clients.linkpreview

import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.URI
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val logger = KotlinLogging.logger {}

/**
 * YouTube OEmbed API response.
 *
 * Spec: https://oembed.com
 */
@Serializable
private data class YouTubeOEmbedResponse(
    val title: String? = null,
    @SerialName("author_name") val authorName: String? = null,
    @SerialName("author_url") val authorUrl: String? = null,
    val type: String? = null,
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
    @SerialName("thumbnail_width") val thumbnailWidth: Int? = null,
    @SerialName("thumbnail_height") val thumbnailHeight: Int? = null,
)

/**
 * Checks if a URL is a YouTube URL.
 *
 * Supports both youtube.com and youtu.be domains with various subdomains (www, m, etc.)
 *
 * @param url The URL to check
 * @return true if the URL is a YouTube URL, false otherwise
 */
fun isYouTubeUrl(url: String): Boolean {
    return try {
        val uri = URI(url)
        val host = uri.host?.lowercase() ?: return false

        // Check for youtube.com and various subdomains (www, m, etc.)
        host == "youtube.com" ||
            host.endsWith(".youtube.com") ||
            host == "youtu.be" ||
            host.endsWith(".youtu.be")
    } catch (_: Exception) {
        false
    }
}

/**
 * Parses a YouTube OEmbed API response into a LinkPreview.
 *
 * The YouTube OEmbed API doesn't provide a description field, so we use the author_name as the
 * description to provide context about the video creator.
 *
 * @param jsonResponse The JSON response from the YouTube OEmbed API
 * @param fallbackUrl The URL to use if parsing fails
 * @return A LinkPreview if parsing succeeds, null otherwise
 */
fun parseYouTubeOEmbedResponse(jsonResponse: String, fallbackUrl: String): LinkPreview? {
    return try {
        val json = Json { ignoreUnknownKeys = true }
        val response = json.decodeFromString<YouTubeOEmbedResponse>(jsonResponse)

        // Validate that we have at least a title
        val title = response.title?.takeIf { it.isNotBlank() } ?: return null

        // Use author_name as description since YouTube OEmbed doesn't provide a description field
        val description = response.authorName?.takeIf { it.isNotBlank() }

        // Use thumbnail_url as the image
        val image = response.thumbnailUrl?.takeIf { it.isNotBlank() }

        LinkPreview(title = title, description = description, url = fallbackUrl, image = image)
    } catch (e: Exception) {
        logger.warn(e) { "Failed to parse YouTube OEmbed response" }
        null
    }
}
