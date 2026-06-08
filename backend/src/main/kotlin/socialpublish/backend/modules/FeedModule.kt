package socialpublish.backend.modules

import arrow.core.getOrElse
import arrow.core.raise.context.bind
import arrow.core.raise.context.either
import arrow.core.raise.context.raise
import com.rometools.rome.feed.synd.SyndCategoryImpl
import com.rometools.rome.feed.synd.SyndContentImpl
import com.rometools.rome.feed.synd.SyndEntryImpl
import com.rometools.rome.feed.synd.SyndFeedImpl
import com.rometools.rome.io.SyndFeedOutput
import java.util.Date
import org.jdom2.Element
import org.jdom2.Namespace
import socialpublish.backend.common.ApiResult
import socialpublish.backend.common.CaughtException
import socialpublish.backend.common.NewFeedPostResponse
import socialpublish.backend.common.NewPostRequest
import socialpublish.backend.common.NewPostRequestMessage
import socialpublish.backend.common.NewPostResponse
import socialpublish.backend.common.PublishedMessageResponse
import socialpublish.backend.common.Target
import socialpublish.backend.common.loggerFactory
import socialpublish.backend.common.rethrowIfFatalOrCancelled
import socialpublish.backend.db.FilesDatabase
import socialpublish.backend.db.Post
import socialpublish.backend.db.PostPayload
import socialpublish.backend.db.PostsDatabase
import socialpublish.backend.db.UUIDv7
import socialpublish.backend.db.UserSession
import socialpublish.backend.server.userUuid

class FeedModule(
    private val baseUrl: String,
    private val postsDb: PostsDatabase,
    private val filesDb: FilesDatabase,
) {
    private fun validateMessages(
        messages: List<NewPostRequestMessage>
    ): CaughtException? {
        val invalid = messages.any { message ->
            message.content.isEmpty() || message.content.length > 1000
        }
        return if (invalid) {
            CaughtException(
                status = 400,
                module = "feed",
                errorMessage = "Content must be between 1 and 1000 characters",
            )
        } else {
            null
        }
    }

    /**
     * Public validation entry point for preflight checks that must run before
     * any publishing side effects (feed or external).
     */
    fun validateRequest(request: NewPostRequest): CaughtException? =
        validateMessages(request.messages)

    /** Create a new feed post (context-based, for routes) */
    context(_: UserSession)
    suspend fun createPost(
        request: NewPostRequest
    ): ApiResult<NewPostResponse> = either {
        try {
            val userUuid = userUuid()
            validateMessages(request.messages)?.let { raise(it) }
            createPosts(
                    targets = request.targets ?: listOf(Target.Feed),
                    language = request.language,
                    messages = request.messages,
                    userUuid = userUuid,
                )
                .bind()
        } catch (e: Throwable) {
            rethrowIfFatalOrCancelled(e)
            logger.error("Failed to save feed item", e)
            raise(
                CaughtException(
                    status = 500,
                    module = "feed",
                    errorMessage = "Failed to save feed item: ${e.message}",
                )
            )
        }
    }

    /**
     * Create feed posts with threading support (explicit userUuid, for
     * PublishModule)
     */
    suspend fun createPosts(
        targets: List<Target>,
        language: String?,
        messages: List<NewPostRequestMessage>,
        userUuid: UUIDv7,
    ): ApiResult<NewPostResponse> = either {
        validateMessages(messages)?.let { raise(it) }
        val dbTargets = targets.map { it.serialName }
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

            val post =
                postsDb
                    .create(payload, dbTargets, userUuid)
                    .mapLeft { error ->
                        logger.error("Failed to save feed item", error)
                        CaughtException(
                            status = 500,
                            module = "feed",
                            errorMessage = "Failed to save feed item",
                        )
                    }
                    .bind()
            val postUri = "$baseUrl/feed/$userUuid/${post.uuid}"
            messageResponses +=
                PublishedMessageResponse(
                    id = post.uuid,
                    uri = postUri,
                    replyToId = previousPostUuid,
                )
            previousPostUuid = post.uuid
        }

        val firstUri =
            messageResponses.firstOrNull()?.uri ?: "$baseUrl/feed/$userUuid"
        NewFeedPostResponse(uri = firstUri, messages = messageResponses)
    }

    /** Generate Atom feed */
    suspend fun generateFeed(
        userUuid: UUIDv7,
        filterByLinks: String? = null,
        filterByImages: String? = null,
        target: String? = null,
    ): String {
        val posts = postsDb.getAllForUser(userUuid).getOrElse { throw it }
        val mediaNamespace =
            Namespace.getNamespace("media", "http://search.yahoo.com/mrss/")
        val threadingNamespace =
            Namespace.getNamespace(
                "thr",
                "http://purl.org/syndication/thread/1.0",
            )

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
                    filesDb
                        .getFileByUuidForUser(imageUuid, userUuid)
                        .getOrElse { throw it } ?: continue
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
                        categories = categoryNames.map { name ->
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

    /** Get feed item by UUID */
    suspend fun getFeedItemByUuid(userUuid: UUIDv7, uuid: String): Post? {
        return postsDb.searchByUuidForUser(uuid, userUuid).getOrElse {
            throw it
        }
    }
}

private val logger by loggerFactory()
