package socialpublish.backend.modules

import arrow.core.raise.either
import arrow.core.raise.raise
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.isRedirect
import io.ktor.http.isSuccess
import org.jsoup.Jsoup

data class LinkPreviewMetadata(
    val title: String,
    val description: String?,
    val thumbnail: String?,
)

class LinkPreviewFetcher(private val httpClient: HttpClient) {
    private val logger = KotlinLogging.logger {}

    suspend fun fetch(url: String): LinkPreviewMetadata? =
        either {
                val response =
                    runCatching { httpClient.get(url) }
                        .onFailure { logger.warn(it) { "Link preview fetch failed for $url" } }
                        .getOrElse { raise(null) }

                if (response.status.isRedirect()) {
                    logger.warn { "Link preview blocked by redirect for $url" }
                    raise(null)
                }

                if (!response.status.isSuccess()) {
                    logger.warn { "Link preview fetch returned ${response.status} for $url" }
                    raise(null)
                }

                val html = runCatching { response.body<String>() }.getOrNull() ?: raise(null)
                val doc = runCatching { Jsoup.parse(html) }.getOrNull() ?: raise(null)

                val title = doc.selectFirst("meta[property=og:title]")?.attr("content")
                    ?: doc.title().takeIf { it.isNotBlank() }
                    ?: raise(null)
                val description =
                    doc.selectFirst("meta[property=og:description]")?.attr("content")
                        ?.takeIf { it.isNotBlank() }
                        ?: doc.selectFirst("meta[name=description]")?.attr("content")?.takeIf {
                            it.isNotBlank()
                        }
                val image =
                    doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf {
                        it.isNotBlank()
                    }

                LinkPreviewMetadata(
                    title = title,
                    description = description,
                    thumbnail = image,
                )
            }
            .getOrNull()
}
