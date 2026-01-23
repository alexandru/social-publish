package socialpublish.backend.clients.linkpreview

import arrow.fx.coroutines.Resource
import arrow.fx.coroutines.resource
import arrow.fx.coroutines.resourceScope
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

private val logger = KotlinLogging.logger {}

/** Parser for extracting link previews from HTML content. */
class LinkPreviewParser(private val httpClient: HttpClient) {
    companion object {
        suspend fun <A> scoped(block: suspend (LinkPreviewParser) -> A): A = resourceScope {
            LinkPreviewParser().bind().let { parser -> block(parser) }
        }

        operator fun invoke(): Resource<LinkPreviewParser> = resource {
            val httpClient =
                install(
                    {
                        HttpClient(CIO) {
                            // Disable automatic redirects to detect bot blocking
                            followRedirects = false
                            expectSuccess = false
                        }
                    },
                    { client, _ -> client.close() },
                )
            LinkPreviewParser(httpClient)
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
        val url = extractUrl(doc) ?: fallbackUrl
        val image = extractImage(doc, fallbackUrl)

        return LinkPreview(title = title, description = description, url = url, image = image)
    }

    /**
     * Fetches a URL and extracts link preview metadata.
     *
     * Prevents redirects to avoid bot detection (e.g., YouTube). If a redirect is detected, this
     * function returns null.
     *
     * @param url The URL to fetch
     * @param httpClient Optional HTTP client (defaults to a client with redirects disabled)
     * @return A LinkPreview object if successful, null if redirect detected or fetch failed
     */
    suspend fun fetchPreview(url: String): LinkPreview? {
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

    private fun extractUrl(doc: Document): String? {
        // Priority 1: Open Graph
        doc.select("meta[property=og:url]")
            .firstOrNull()
            ?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?.let {
                return it
            }

        // Priority 2: Twitter Cards
        doc.select("meta[name=twitter:url]")
            .firstOrNull()
            ?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?.let {
                return it
            }

        return null
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
