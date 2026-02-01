package socialpublish.backend.clients.linkpreview

import arrow.fx.coroutines.Resource
import arrow.fx.coroutines.resource
import arrow.fx.coroutines.resourceScope
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.URLBuilder
import io.ktor.http.isSuccess
import java.net.URI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

private val logger = KotlinLogging.logger {}

/**
 * Configuration for LinkPreviewParser HTTP client timeouts.
 *
 * @param requestTimeout Total request timeout
 * @param connectTimeout Connection establishment timeout
 * @param socketTimeout Socket read/write timeout
 */
data class LinkPreviewConfig(
    val requestTimeout: Duration = 30.seconds,
    val connectTimeout: Duration = 10.seconds,
    val socketTimeout: Duration = 20.seconds,
)

/** Parser for extracting link previews from HTML content. */
class LinkPreviewParser(
    private val httpClient: HttpClient,
    private val config: LinkPreviewConfig = LinkPreviewConfig(),
) {
    companion object {
        suspend fun <A> scoped(
            config: LinkPreviewConfig = LinkPreviewConfig(),
            block: suspend (LinkPreviewParser) -> A,
        ): A = resourceScope { LinkPreviewParser(config).bind().let { parser -> block(parser) } }

        operator fun invoke(
            config: LinkPreviewConfig = LinkPreviewConfig()
        ): Resource<LinkPreviewParser> = resource {
            val httpClient =
                install(
                    {
                        HttpClient(CIO) {
                            // Disable automatic redirects to detect bot blocking
                            followRedirects = false
                            expectSuccess = false

                            // Configure timeouts to prevent hanging on unresponsive servers
                            install(io.ktor.client.plugins.HttpTimeout) {
                                requestTimeoutMillis = config.requestTimeout.inWholeMilliseconds
                                connectTimeoutMillis = config.connectTimeout.inWholeMilliseconds
                                socketTimeoutMillis = config.socketTimeout.inWholeMilliseconds
                            }
                        }
                    },
                    { client, _ -> client.close() },
                )
            LinkPreviewParser(httpClient, config)
        }
    }

    /**
     * Parses HTML content to extract link preview metadata.
     *
     * Attempts to extract metadata in the following priority order:
     * 1. Open Graph tags (og:title, og:description, og:url, og:image)
     * 2. Twitter Cards tags (twitter:title, twitter:description, twitter:url, twitter:image)
     * 3. Standard HTML tags (<title>, <meta name="description">)
     *
     * @param html The HTML content to parse
     * @param fallbackUrl The URL to use if no URL is found in the metadata
     * @return A LinkPreview object if metadata was found, null otherwise
     */
    fun parseHtml(html: String, fallbackUrl: String): LinkPreview? {
        val doc = Jsoup.parse(html)

        // Try to extract metadata using priority order
        val title = extractTitle(doc) ?: return null
        val description = extractDescription(doc)
        val url = extractUrl(doc, fallbackUrl) ?: fallbackUrl
        val image = extractImage(doc, fallbackUrl)

        return LinkPreview(title = title, description = description, url = url, image = image)
    }

    /**
     * Fetches a URL and extracts link preview metadata.
     *
     * For YouTube URLs, uses the YouTube OEmbed API to avoid bot detection. For other URLs, fetches
     * the HTML content directly. Prevents redirects to avoid bot detection. If a redirect is
     * detected, this function returns null.
     *
     * @param url The URL to fetch
     * @return A LinkPreview object if successful, null if redirect detected or fetch failed
     */
    suspend fun fetchPreview(url: String): LinkPreview? {
        // Use YouTube OEmbed API for YouTube URLs to avoid bot blocking
        if (isYouTubeUrl(url)) {
            return fetchYouTubeOEmbed(url)
        }

        return try {
            val response = httpClient.get(url)

            if (!response.status.isSuccess()) {
                logger.warn { "Failed to fetch URL $url: ${response.status}" }
                return null
            }

            val html = response.bodyAsText()
            parseHtml(html, url)
        } catch (e: Exception) {
            logger.warn(e) { "Error fetching link preview for $url" }
            null
        }
    }

    /**
     * Fetches YouTube video metadata using the YouTube OEmbed API.
     *
     * Does not fallback to HTML fetching if the OEmbed API fails, as YouTube blocks bots and
     * servers.
     *
     * Requests larger thumbnails by setting maxwidth and maxheight parameters to get better quality
     * preview images.
     *
     * @param url The YouTube URL to fetch metadata for
     * @return A LinkPreview if successful, null otherwise
     */
    private suspend fun fetchYouTubeOEmbed(url: String): LinkPreview? {
        return try {
            // Build URL with proper encoding to prevent injection attacks
            val oembedUrl =
                URLBuilder("https://www.youtube.com/oembed")
                    .apply {
                        parameters.append("url", url)
                        parameters.append("format", "json")
                        // Request larger thumbnails for better quality preview images (e.g.,
                        // 1280x720
                        // instead of 480x360)
                        parameters.append("maxwidth", "1280")
                        parameters.append("maxheight", "720")
                    }
                    .buildString()

            val response = httpClient.get(oembedUrl)

            if (!response.status.isSuccess()) {
                logger.warn { "Failed to fetch YouTube OEmbed for $url: ${response.status}" }
                return null
            }

            val json = response.bodyAsText()
            parseYouTubeOEmbedResponse(json, url)
        } catch (e: Exception) {
            logger.warn(e) { "Error fetching YouTube OEmbed for $url" }
            null
        }
    }

    private fun extractTitle(doc: Document): String? {
        // Priority 1: Open Graph
        doc.select("meta[property=og:title]")
            .firstOrNull()
            ?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?.let {
                return it
            }

        // Priority 2: Twitter Cards
        doc.select("meta[name=twitter:title]")
            .firstOrNull()
            ?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?.let {
                return it
            }

        // Priority 3: Standard HTML title
        doc.select("title")
            .firstOrNull()
            ?.text()
            ?.takeIf { it.isNotBlank() }
            ?.let {
                return it
            }

        return null
    }

    private fun extractDescription(doc: Document): String? {
        // Priority 1: Open Graph
        doc.select("meta[property=og:description]")
            .firstOrNull()
            ?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?.let {
                return it
            }

        // Priority 2: Twitter Cards
        doc.select("meta[name=twitter:description]")
            .firstOrNull()
            ?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?.let {
                return it
            }

        // Priority 3: Standard HTML meta description
        doc.select("meta[name=description]")
            .firstOrNull()
            ?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?.let {
                return it
            }

        return null
    }

    private fun extractUrl(doc: Document, baseUrl: String): String? {
        // Priority 1: Open Graph
        doc.select("meta[property=og:url]")
            .firstOrNull()
            ?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?.let { url ->
                val resolved = resolveUrl(url, baseUrl)
                if (resolved == null) {
                    logger.warn { "Failed to resolve URL '$url' against base URL '$baseUrl'" }
                }
                return resolved
            }

        // Priority 2: Twitter Cards
        doc.select("meta[name=twitter:url]")
            .firstOrNull()
            ?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?.let { url ->
                val resolved = resolveUrl(url, baseUrl)
                if (resolved == null) {
                    logger.warn { "Failed to resolve URL '$url' against base URL '$baseUrl'" }
                }
                return resolved
            }

        return null
    }

    private fun resolveUrl(url: String, baseUrl: String): String? {
        // Validate and parse the base URL once
        val baseUri =
            try {
                val uri = URI(baseUrl)
                if (uri.scheme == null || uri.host == null) {
                    return null
                }
                uri
            } catch (_: Exception) {
                return null
            }

        return when {
            // Already absolute URL (has protocol)
            url.startsWith("http://") || url.startsWith("https://") -> url

            // Protocol-relative URL (starts with //)
            url.startsWith("//") -> {
                val protocol = if (baseUrl.startsWith("https://")) "https:" else "http:"
                "$protocol$url"
            }

            // Root-relative URL (starts with /)
            url.startsWith("/") -> {
                try {
                    "${baseUri.scheme}://${baseUri.host}${if (baseUri.port != -1) ":${baseUri.port}" else ""}$url"
                } catch (_: Exception) {
                    null
                }
            }

            // Path-relative URL - resolve against base URL directory
            else -> {
                try {
                    baseUri.resolve(url).toString()
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    private fun extractImage(doc: Document, baseUrl: String): String? {
        // Priority 1: Open Graph
        doc.select("meta[property=og:image]")
            .firstOrNull()
            ?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?.let { imageUrl ->
                val resolved = resolveImageUrl(imageUrl, baseUrl)
                if (resolved == null) {
                    logger.warn {
                        "Failed to resolve image URL '$imageUrl' against base URL '$baseUrl'"
                    }
                }
                return resolved
            }

        // Priority 2: Twitter Cards (twitter:image)
        doc.select("meta[name=twitter:image]")
            .firstOrNull()
            ?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?.let { imageUrl ->
                val resolved = resolveImageUrl(imageUrl, baseUrl)
                if (resolved == null) {
                    logger.warn {
                        "Failed to resolve image URL '$imageUrl' against base URL '$baseUrl'"
                    }
                }
                return resolved
            }

        // Priority 3: Twitter Cards (twitter:image:src)
        doc.select("meta[name=twitter:image:src]")
            .firstOrNull()
            ?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?.let { imageUrl ->
                val resolved = resolveImageUrl(imageUrl, baseUrl)
                if (resolved == null) {
                    logger.warn {
                        "Failed to resolve image URL '$imageUrl' against base URL '$baseUrl'"
                    }
                }
                return resolved
            }

        return null
    }
}
