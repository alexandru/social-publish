package com.alexn.socialpublish.modules

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.alexn.socialpublish.config.AppConfig
import com.alexn.socialpublish.db.FilesDatabase
import com.alexn.socialpublish.db.PostPayload
import com.alexn.socialpublish.db.PostsDatabase
import com.alexn.socialpublish.models.ApiResult
import com.alexn.socialpublish.models.CaughtException
import com.alexn.socialpublish.models.NewPostRequest
import com.alexn.socialpublish.models.NewPostResponse
import com.alexn.socialpublish.models.NewRssPostResponse
import com.rometools.rome.feed.synd.SyndCategoryImpl
import com.rometools.rome.feed.synd.SyndContentImpl
import com.rometools.rome.feed.synd.SyndEntryImpl
import com.rometools.rome.feed.synd.SyndFeedImpl
import com.rometools.rome.io.SyndFeedOutput
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import java.util.Date

private val logger = KotlinLogging.logger {}

class RssModule(
    private val config: AppConfig,
    private val postsDb: PostsDatabase,
    private val filesDb: FilesDatabase,
) {
    /**
     * Create a new RSS post
     */
    suspend fun createPost(request: NewPostRequest): ApiResult<NewPostResponse> {
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
                Regex("""(?:^|\s)(#\w+)""").findAll(content)
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

            val post = postsDb.create(payload, request.targets ?: emptyList())

            NewRssPostResponse(
                uri = "${config.baseUrl}/rss/${post.uuid}",
            ).right()
        } catch (e: Exception) {
            logger.error(e) { "Failed to save RSS item" }
            CaughtException(
                status = 500,
                module = "rss",
                errorMessage = "Failed to save RSS item: ${e.message}",
            ).left()
        }
    }

    /**
     * Generate RSS feed
     */
    fun generateRss(
        filterByLinks: String? = null,
        filterByImages: String? = null,
        target: String? = null,
    ): String {
        val posts = postsDb.getAll()

        val feed =
            SyndFeedImpl().apply {
                feedType = "rss_2.0"
                title = "Feed of ${config.baseUrl.replace(Regex("^https?://"), "")}"
                link = config.baseUrl
                description = "Social publish RSS feed"
                publishedDate = Date()
            }

        val entries =
            posts.mapNotNull { post ->
                // Apply filters
                if (target != null && !post.targets.contains(target)) {
                    return@mapNotNull null
                }
                if (filterByLinks == "include" && post.link == null) {
                    return@mapNotNull null
                }
                if (filterByLinks == "exclude" && post.link != null) {
                    return@mapNotNull null
                }
                if (filterByImages == "include" && post.images.isNullOrEmpty()) {
                    return@mapNotNull null
                }
                if (filterByImages == "exclude" && !post.images.isNullOrEmpty()) {
                    return@mapNotNull null
                }

                SyndEntryImpl().apply {
                    title = post.content.take(100) + if (post.content.length > 100) "..." else ""
                    link = "${config.baseUrl}/rss/${post.uuid}"
                    publishedDate = Date.from(post.createdAt)

                    val description =
                        SyndContentImpl().apply {
                            type = "text/html"
                            value =
                                buildString {
                                    append("<p>${escapeHtml(post.content)}</p>")
                                    post.link?.let { append("<p><a href=\"$it\">$it</a></p>") }
                                    if (!post.images.isNullOrEmpty()) {
                                        post.images.forEach { imageUuid ->
                                            append("<p><img src=\"${config.baseUrl}/files/$imageUuid\" /></p>")
                                        }
                                    }
                                }
                        }
                    this.description = description

                    // Add categories for tags
                    if (!post.tags.isNullOrEmpty()) {
                        categories =
                            post.tags.map { tag ->
                                SyndCategoryImpl().apply {
                                    name = tag
                                }
                            }
                    }
                }
            }

        feed.entries = entries

        val output = SyndFeedOutput()
        return output.outputString(feed)
    }

    /**
     * Handle RSS post creation HTTP route
     */
    suspend fun createPostRoute(call: ApplicationCall) {
        val params = call.receiveParameters()
        val request =
            NewPostRequest(
                content = params["content"] ?: "",
                targets = params.getAll("targets[]"),
                link = params["link"],
                language = params["language"],
                cleanupHtml = params["cleanupHtml"]?.toBoolean(),
                images = params.getAll("images[]"),
            )

        when (val result = createPost(request)) {
            is Either.Right -> call.respond(result.value)
            is Either.Left -> {
                val error = result.value
                call.respond(HttpStatusCode.fromValue(error.status), mapOf("error" to error.errorMessage))
            }
        }
    }

    /**
     * Handle RSS feed generation HTTP route
     */
    suspend fun generateRssRoute(call: ApplicationCall) {
        val target = call.parameters["target"]
        val filterByLinks = call.request.queryParameters["filterByLinks"]
        val filterByImages = call.request.queryParameters["filterByImages"]

        val rssContent = generateRss(filterByLinks, filterByImages, target)
        call.respondText(rssContent, ContentType.Application.Rss)
    }

    /**
     * Get RSS item by UUID
     */
    suspend fun getRssItem(call: ApplicationCall) {
        val uuid =
            call.parameters["uuid"] ?: run {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing UUID"))
                return
            }

        val post = postsDb.searchByUuid(uuid)
        if (post == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Post not found"))
            return
        }

        call.respond(post)
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

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }
}
