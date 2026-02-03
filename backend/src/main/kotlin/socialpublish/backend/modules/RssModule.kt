package socialpublish.backend.modules

import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import com.rometools.rome.feed.synd.SyndCategoryImpl
import com.rometools.rome.feed.synd.SyndContentImpl
import com.rometools.rome.feed.synd.SyndEntryImpl
import com.rometools.rome.feed.synd.SyndFeedImpl
import com.rometools.rome.io.SyndFeedOutput
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.Date
import org.jdom2.Element
import org.jdom2.Namespace
import socialpublish.backend.common.ApiResult
import socialpublish.backend.common.CaughtException
import socialpublish.backend.common.NewPostRequest
import socialpublish.backend.common.NewPostResponse
import socialpublish.backend.common.NewRssPostResponse
import socialpublish.backend.db.FilesDatabase
import socialpublish.backend.db.Post
import socialpublish.backend.db.PostPayload
import socialpublish.backend.db.PostsDatabase

private val logger = KotlinLogging.logger {}

class RssModule(
    private val baseUrl: String,
    private val postsDb: PostsDatabase,
    private val filesDb: FilesDatabase,
) {
    /** Create a new RSS post */
    suspend fun createPost(
        userUuid: java.util.UUID,
        request: NewPostRequest,
    ): ApiResult<NewPostResponse> {
        return try {
            // Validate request
            request.validate()?.let { error ->
                return error.left()
            }

            val content =
                if (request.cleanupHtml == true) {
                    cleanupHtml(request.content)
                } else {
                    request.content
                }

            // Extract hashtags
            val tags =
                Regex("""(?:^|\s)(#\w+)""")
                    .findAll(content)
                    .map { it.value.trim().substring(1) }
                    .toList()

            val payload =
                PostPayload(
                    content = content,
                    link = request.link,
                    language = request.language,
                    tags = tags,
                    images = request.images,
                )

            val post =
                postsDb.create(userUuid, payload, request.targets ?: emptyList()).getOrElse {
                    throw it
                }

            NewRssPostResponse(uri = "$baseUrl/rss/${post.uuid}").right()
        } catch (e: Exception) {
            logger.error(e) { "Failed to save RSS item" }
            CaughtException(
                    status = 500,
                    module = "rss",
                    errorMessage = "Failed to save RSS item: ${e.message}",
                )
                .left()
        }
    }

    /** Generate RSS feed */
    suspend fun generateRss(
        userUuid: java.util.UUID,
        filterByLinks: String? = null,
        filterByImages: String? = null,
        target: String? = null,
    ): String {
        val posts = postsDb.getAll(userUuid).getOrElse { throw it }
        val mediaNamespace = Namespace.getNamespace("media", "http://search.yahoo.com/mrss/")

        val feed =
            SyndFeedImpl().apply {
                feedType = "rss_2.0"
                title = "Feed of ${baseUrl.replace(Regex("^https?://"), "")}"
                link = baseUrl
                uri = "$baseUrl/rss"
                description = "Social publish RSS feed"
                publishedDate = Date()
            }

        val entries = mutableListOf<SyndEntryImpl>()
        for (post in posts) {
            if (target != null && !post.targets.contains(target)) {
                continue
            }
            if (filterByLinks == "include" && post.link == null) {
                continue
            }
            if (filterByLinks == "exclude" && post.link != null) {
                continue
            }
            if (filterByImages == "include" && post.images.isNullOrEmpty()) {
                continue
            }
            if (filterByImages == "exclude" && !post.images.isNullOrEmpty()) {
                continue
            }

            val guid = "$baseUrl/rss/${post.uuid}"
            val mediaElements = mutableListOf<Element>()

            for (imageUuid in post.images.orEmpty()) {
                val upload =
                    filesDb.getFileByUuid(userUuid, imageUuid).getOrElse { throw it } ?: continue
                val content = Element("content", mediaNamespace)
                content.setAttribute("url", "$baseUrl/files/${upload.uuid}")
                content.setAttribute("fileSize", upload.size.toString())
                content.setAttribute("type", upload.mimetype)

                val rating = Element("rating", mediaNamespace)
                rating.setAttribute("scheme", "urn:simple")
                rating.text = "nonadult"
                content.addContent(rating)

                upload.altText?.let { altText ->
                    val description = Element("description", mediaNamespace)
                    description.text = altText
                    content.addContent(description)
                }

                mediaElements.add(content)
            }

            val categoryNames = mutableListOf<String>()
            post.tags?.let { categoryNames.addAll(it) }
            if (post.targets.isNotEmpty()) {
                categoryNames.addAll(post.targets)
            }

            entries +=
                SyndEntryImpl().apply {
                    title = post.content
                    link = post.link ?: guid
                    uri = guid
                    publishedDate = Date.from(post.createdAt)
                    description =
                        SyndContentImpl().apply {
                            type = "text/plain"
                            value = post.content
                        }

                    if (categoryNames.isNotEmpty()) {
                        categories =
                            categoryNames.map { name ->
                                SyndCategoryImpl().apply { this.name = name }
                            }
                    }

                    if (mediaElements.isNotEmpty()) {
                        foreignMarkup = mediaElements
                    }
                }
        }

        feed.entries = entries

        val output = SyndFeedOutput()
        return output.outputString(feed)
    }

    /** Get RSS item by UUID */
    suspend fun getRssItemByUuid(userUuid: java.util.UUID, uuid: String): Post? {
        return postsDb.searchByUuid(userUuid, uuid).getOrElse { throw it }
    }

    private fun cleanupHtml(html: String): String {
        // Basic HTML cleanup - remove tags but keep content
        return html
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .trim()
    }
}
