package socialpublish.backend.modules

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.isRedirect
import io.ktor.http.isSuccess
import org.jsoup.Jsoup

data class LinkPreviewMetadata(val title: String, val description: String?, val thumbnail: String?)

class LinkPreviewFetcher(private val httpClient: HttpClient) {
    private val logger = KotlinLogging.logger {}

    suspend fun fetch(url: String): LinkPreviewMetadata? {
        val response =
            runCatching { httpClient.get(url) }
                .onFailure { logger.warn(it) { "Link preview fetch failed for $url" } }
                .getOrNull()
                ?: return null

        if (response.status.isRedirect()) {
            logger.warn { "Link preview blocked by redirect for $url" }
            return null
        }

        if (!response.status.isSuccess()) {
            logger.warn { "Link preview fetch returned ${response.status} for $url" }
            return null
        }

        val html = runCatching { response.body<String>() }.getOrNull() ?: return null
        val doc = runCatching { Jsoup.parse(html) }.getOrNull() ?: return null

        val title =
            doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: doc.title().takeIf { it.isNotBlank() }
                ?: return null
        val description =
            doc.selectFirst("meta[property=og:description]")?.attr("content")?.takeIf {
                it.isNotBlank()
            }
                ?: doc.selectFirst("meta[name=description]")?.attr("content")?.takeIf {
                    it.isNotBlank()
                }
        val image =
            doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf {
                it.isNotBlank()
            }

        return LinkPreviewMetadata(title = title, description = description, thumbnail = image)
    }
}
