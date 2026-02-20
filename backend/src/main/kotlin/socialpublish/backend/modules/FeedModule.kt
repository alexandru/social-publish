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
import java.util.UUID
import org.jdom2.Element
import org.jdom2.Namespace
import socialpublish.backend.common.ApiResult
import socialpublish.backend.common.CaughtException
import socialpublish.backend.common.NewFeedPostResponse
import socialpublish.backend.common.NewPostRequest
import socialpublish.backend.common.NewPostRequestMessage
import socialpublish.backend.common.NewPostResponse
import socialpublish.backend.common.PublishedMessageResponse
import socialpublish.backend.db.FilesDatabase
import socialpublish.backend.db.Post
import socialpublish.backend.db.PostPayload
import socialpublish.backend.db.PostsDatabase

private val logger = KotlinLogging.logger {}

class FeedModule(
    private val baseUrl: String,
    private val postsDb: PostsDatabase,
    private val filesDb: FilesDatabase,
) {
    suspend fun createPost(request: NewPostRequest, userUuid: UUID): ApiResult<NewPostResponse> {
        return try {
            request.validate()?.let { error ->
                return error.left()
            }

            createPosts(
                targets = request.targets ?: listOf("feed"),
                language = request.language,
                messages = request.messages,
                userUuid = userUuid,
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to save feed item" }
            CaughtException(
                    status = 500,
                    module = "feed",
                    errorMessage = "Failed to save feed item: ${e.message}",
                )
                .left()
        }
    }

    suspend fun createPosts(
        targets: List<String>,
        language: String?,
        messages: List<NewPostRequestMessage>,
        userUuid: UUID,
    ): ApiResult<NewPostResponse> {
        val normalizedTargets = targets.map { it.lowercase() }
        var previousPostUuid: String? = null
        val messageResponses = mutableListOf<PublishedMessageResponse>()

        for (message in messages) {
            val tags =
                Regex("""(?:^|\s)(#\w+)""")
                    .findAll(message.content)
                    .map { it.value.trim().substring(1) }
                    .toList()

            val payload =
                PostPayload(
                    content = message.content,
                    link = message.link,
                    language = language,
                    tags = tags,
                    images = message.images,
                    replyToPostUuid = previousPostUuid,
                )

            val postResult =
                try {
                    postsDb.create(payload, normalizedTargets, userUuid)
                } catch (e: Exception) {
                    logger.error(e) { "Failed to save feed item" }
                    return CaughtException(
                            status = 500,
                            module = "feed",
                            errorMessage = "Failed to save feed item: ${e.message}",
                        )
                        .left()
                }
            val post =
                when (postResult) {
                    is arrow.core.Either.Right -> postResult.value
                    is arrow.core.Either.Left -> {
                        logger.error(postResult.value) { "Failed to save feed item" }
                        return CaughtException(
                                status = 500,
                                module = "feed",
                                errorMessage = "Failed to save feed item",
                            )
                            .left()
                    }
                }
            val postUri = "$baseUrl/feed/$userUuid/${post.uuid}"
            messageResponses +=
                PublishedMessageResponse(
                    id = post.uuid,
                    uri = postUri,
                    replyToId = previousPostUuid,
                )
            previousPostUuid = post.uuid
        }

        val firstUri = messageResponses.firstOrNull()?.uri ?: "$baseUrl/feed/$userUuid"
        return NewFeedPostResponse(uri = firstUri, messages = messageResponses).right()
    }

    suspend fun generateFeed(
        userUuid: UUID,
        filterByLinks: String? = null,
        filterByImages: String? = null,
        target: String? = null,
    ): String {
        val posts = postsDb.getAllForUser(userUuid).getOrElse { throw it }.asReversed()
        val mediaNamespace = Namespace.getNamespace("media", "http://search.yahoo.com/mrss/")
        val threadingNamespace =
            Namespace.getNamespace("thr", "http://purl.org/syndication/thread/1.0")

        val feed =
            SyndFeedImpl().apply {
                feedType = "atom_1.0"
                title = "Feed of ${baseUrl.replace(Regex("^https?://"), "")}"
                link = baseUrl
                uri = "$baseUrl/feed"
                description = "Social publish feed"
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

            val guid = "$baseUrl/feed/$userUuid/${post.uuid}"
            val mediaElements = mutableListOf<Element>()

            for (imageUuid in post.images.orEmpty()) {
                val upload =
                    filesDb.getFileByUuidForUser(imageUuid, userUuid).getOrElse { throw it }
                        ?: continue
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

            val entry =
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

                    val foreignMarkupElements = mutableListOf<Element>()
                    if (mediaElements.isNotEmpty()) {
                        foreignMarkupElements.addAll(mediaElements)
                    }

                    post.replyToPostUuid?.let { parentUuid ->
                        val parentUri = "$baseUrl/feed/$userUuid/$parentUuid"
                        foreignMarkupElements +=
                            Element("in-reply-to", threadingNamespace).apply {
                                setAttribute("ref", parentUri)
                                setAttribute("href", parentUri)
                                setAttribute("type", "text/html")
                            }
                    }

                    if (foreignMarkupElements.isNotEmpty()) {
                        foreignMarkup = foreignMarkupElements
                    }
                }

            entries += entry
        }

        feed.entries = entries

        val output = SyndFeedOutput()
        return output.outputString(feed)
    }

    suspend fun getFeedItemByUuid(userUuid: UUID, uuid: String): Post? {
        return postsDb.searchByUuidForUser(uuid, userUuid).getOrElse { throw it }
    }
}
