package socialpublish.backend.clients.bluesky

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import arrow.fx.coroutines.Resource
import arrow.fx.coroutines.resource
import arrow.fx.coroutines.resourceScope
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.utils.io.ByteReadChannel
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import socialpublish.backend.clients.linkpreview.LinkPreviewParser
import socialpublish.backend.common.ApiResult
import socialpublish.backend.common.CaughtException
import socialpublish.backend.common.ErrorResponse
import socialpublish.backend.common.NewBlueSkyPostResponse
import socialpublish.backend.common.NewPostRequest
import socialpublish.backend.common.NewPostResponse
import socialpublish.backend.common.RequestError
import socialpublish.backend.common.ResponseBody
import socialpublish.backend.common.ValidationError
import socialpublish.backend.modules.FilesModule

private val logger = KotlinLogging.logger {}

private const val BlueskyLinkDisplayLength = 24

/** Bluesky API module implementing AT Protocol */
class BlueskyApiModule(
    private val config: BlueskyConfig,
    private val filesModule: FilesModule,
    private val httpClient: HttpClient,
    private val linkPreviewParser: LinkPreviewParser,
) {
    companion object {
        fun defaultHttpClient(): Resource<HttpClient> = resource {
            install({
                HttpClient(CIO) {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }
            }) { client, _ ->
                client.close()
            }
        }

        fun resource(config: BlueskyConfig, filesModule: FilesModule): Resource<BlueskyApiModule> =
            resource {
                BlueskyApiModule(
                    config = config,
                    filesModule = filesModule,
                    linkPreviewParser = LinkPreviewParser().bind(),
                    httpClient = defaultHttpClient().bind(),
                )
            }
    }

    /** Login to Bluesky and get session token */
    private suspend fun createSession(): ApiResult<BlueskySessionResponse> {
        return try {
            val response =
                httpClient.post("${config.service}/xrpc/com.atproto.server.createSession") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        buildJsonObject {
                            put("identifier", config.username)
                            put("password", config.password)
                        }
                    )
                }

            if (response.status.value == 200) {
                val session = response.body<BlueskySessionResponse>()
                logger.info { "Authenticated to Bluesky as ${session.handle}" }
                session.right()
            } else {
                val errorBody = response.bodyAsText()
                logger.warn {
                    "Failed to authenticate to Bluesky: ${response.status}, body: $errorBody"
                }
                RequestError(
                        status = response.status.value,
                        module = "bluesky",
                        errorMessage = "Failed to authenticate to Bluesky",
                        body = ResponseBody(asString = errorBody),
                    )
                    .left()
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to authenticate to Bluesky" }
            CaughtException(
                    status = 500,
                    module = "bluesky",
                    errorMessage = "Failed to authenticate to Bluesky: ${e.message}",
                )
                .left()
        }
    }

    /** Upload image blob to Bluesky */
    private suspend fun uploadBlob(
        uuid: String,
        session: BlueskySessionResponse,
    ): ApiResult<BlueskyImageEmbed> = resourceScope {
        return try {
            val file =
                filesModule.readImageFile(uuid)
                    ?: return ValidationError(
                            status = 404,
                            errorMessage = "Failed to read image file — uuid: $uuid",
                            module = "bluesky",
                        )
                        .left()

            val response =
                httpClient.post("${config.service}/xrpc/com.atproto.repo.uploadBlob") {
                    header("Authorization", "Bearer ${session.accessJwt}")
                    contentType(ContentType.parse(file.mimetype))
                    setBody(ByteReadChannel(file.source.asKotlinSource().bind()))
                }

            if (response.status.value == 200) {
                val blobData = response.body<JsonObject>()
                val blob =
                    blobData["blob"] as? JsonObject
                        ?: return CaughtException(
                                status = 500,
                                module = "bluesky",
                                errorMessage = "Invalid blob response",
                            )
                            .left()

                val blobRef =
                    BlueskyBlobRef(
                        `$type` = "blob",
                        ref = blob["ref"] as JsonObject,
                        mimeType = file.mimetype,
                        size = file.size.toInt(),
                    )

                return BlueskyImageEmbed(
                        alt = file.altText ?: "",
                        image = blobRef,
                        aspectRatio =
                            if (file.width > 0 && file.height > 0) {
                                BlueskyAspectRatio(width = file.width, height = file.height)
                            } else {
                                null
                            },
                    )
                    .right()
            }

            val errorBody = response.bodyAsText()
            logger.warn { "Failed to upload blob to Bluesky: ${response.status}, body: $errorBody" }
            RequestError(
                    status = response.status.value,
                    module = "bluesky",
                    errorMessage = "Failed to upload blob",
                    body = ResponseBody(asString = errorBody),
                )
                .left()
        } catch (e: Exception) {
            logger.error(e) { "Failed to upload blob (bluesky) — uuid $uuid" }
            CaughtException(
                    status = 500,
                    module = "bluesky",
                    errorMessage = "Failed to upload blob — uuid: $uuid",
                )
                .left()
        }
    }

    /** Upload image from URL as blob to Bluesky for link previews */
    private suspend fun uploadBlobFromUrl(
        imageUrl: String,
        session: BlueskySessionResponse,
    ): BlueskyBlobRef? {
        return try {
            // Fetch the image
            val response = httpClient.get(imageUrl)

            if (response.status.value != 200) {
                logger.warn { "Failed to fetch image from $imageUrl: ${response.status}" }
                return null
            }

            val imageBytes = response.body<ByteArray>()
            val contentType = response.contentType()?.toString() ?: "image/jpeg"

            // Upload to Bluesky
            val uploadResponse =
                httpClient.post("${config.service}/xrpc/com.atproto.repo.uploadBlob") {
                    header("Authorization", "Bearer ${session.accessJwt}")
                    contentType(ContentType.parse(contentType))
                    setBody(imageBytes)
                }

            if (uploadResponse.status.isSuccess()) {
                val blobData = uploadResponse.body<JsonObject>()
                val blob = blobData["blob"] as? JsonObject ?: return null

                return BlueskyBlobRef(
                    `$type` = "blob",
                    ref = blob["ref"] as JsonObject,
                    mimeType = contentType,
                    size = imageBytes.size,
                )
            }

            logger.warn { "Failed to upload blob from URL to Bluesky: ${uploadResponse.status}" }
            null
        } catch (e: Exception) {
            logger.warn(e) { "Failed to upload blob from URL $imageUrl" }
            null
        }
    }

    @Serializable data class DidResolutionResponse(val did: String)

    // ... (existing code)

    /** Resolve a handle to a DID using the Bluesky API */
    private suspend fun resolveHandle(handle: String): String? {
        return try {
            val response =
                httpClient.get("${config.service}/xrpc/com.atproto.identity.resolveHandle") {
                    url { parameters.append("handle", handle) }
                }

            if (response.status.value == 200) {
                val data = response.body<DidResolutionResponse>()
                data.did
            } else {
                logger.warn { "Failed to resolve handle $handle: ${response.status}" }
                null
            }
        } catch (e: Exception) {
            logger.error(e) { "Error resolving handle $handle" }
            null
        }
    }

    private data class RichTextPayload(val text: String, val facets: List<JsonObject>)

    private fun utf8Length(value: String): Int = value.toByteArray(Charsets.UTF_8).size

    private fun shortenLinkForDisplay(
        url: String,
        maxLength: Int = BlueskyLinkDisplayLength,
    ): String {
        if (url.length <= maxLength) {
            return url
        }

        if (maxLength <= 3) {
            return url.take(maxLength)
        }

        return url.take(maxLength - 3) + "..."
    }

    private fun buildLinkFacet(byteStart: Int, byteEnd: Int, uri: String): JsonObject {
        return buildJsonObject {
            putJsonObject("index") {
                put("byteStart", byteStart)
                put("byteEnd", byteEnd)
            }
            putJsonArray("features") {
                add(
                    buildJsonObject {
                        put("\$type", "app.bsky.richtext.facet#link")
                        put("uri", uri)
                    }
                )
            }
        }
    }

    /**
     * Detect facets (mentions, hashtags) in text.
     *
     * IMPORTANT: Calculates byte offsets using UTF-8 to match AT Protocol spec.
     */
    private suspend fun detectMentionsAndTags(text: String): List<JsonObject> {
        val facets = mutableListOf<JsonObject>()

        // 1. Detect Mentions (@handle.bsky.social)
        val mentionRegex = Regex("""(?<=\s|^)(@[a-zA-Z0-9.-]+)""")
        for (match in mentionRegex.findAll(text)) {
            val handle = match.value.substring(1) // Remove '@'
            // Only resolve if it looks like a valid handle (has at least one dot)
            if (handle.contains(".")) {
                val did = resolveHandle(handle)
                if (did != null) {
                    val byteStart = utf8Length(text.substring(0, match.range.first))
                    val byteEnd = byteStart + utf8Length(match.value)

                    val facet = buildJsonObject {
                        putJsonObject("index") {
                            put("byteStart", byteStart)
                            put("byteEnd", byteEnd)
                        }
                        putJsonArray("features") {
                            add(
                                buildJsonObject {
                                    put("\$type", "app.bsky.richtext.facet#mention")
                                    put("did", did)
                                }
                            )
                        }
                    }
                    facets.add(facet)
                }
            }
        }

        // 2. Detect Hashtags (#tag)
        val tagRegex = Regex("""(?<=\s|^)(#[a-zA-Z0-9]+)""")
        tagRegex.findAll(text).forEach { match ->
            val tag = match.value.substring(1) // Remove '#'
            val byteStart = utf8Length(text.substring(0, match.range.first))
            val byteEnd = byteStart + utf8Length(match.value)

            val facet = buildJsonObject {
                putJsonObject("index") {
                    put("byteStart", byteStart)
                    put("byteEnd", byteEnd)
                }
                putJsonArray("features") {
                    add(
                        buildJsonObject {
                            put("\$type", "app.bsky.richtext.facet#tag")
                            put("tag", tag)
                        }
                    )
                }
            }
            facets.add(facet)
        }

        return facets
    }

    /**
     * Build rich text payload with shortened link display text and facets.
     *
     * IMPORTANT: Calculates byte offsets using UTF-8 to match AT Protocol spec.
     */
    private suspend fun buildRichText(text: String): RichTextPayload {
        val urlRegex = Regex("""(?<=\s|^)(https?://[^\s]+)""")
        val facets = mutableListOf<JsonObject>()
        val builder = StringBuilder()
        var byteOffset = 0
        var lastIndex = 0

        for (match in urlRegex.findAll(text)) {
            val prefix = text.substring(lastIndex, match.range.first)
            builder.append(prefix)
            byteOffset += utf8Length(prefix)

            val displayText = shortenLinkForDisplay(match.value)
            val byteStart = byteOffset
            val byteEnd = byteStart + utf8Length(displayText)

            builder.append(displayText)
            byteOffset = byteEnd

            facets.add(buildLinkFacet(byteStart, byteEnd, match.value))
            lastIndex = match.range.last + 1
        }

        if (lastIndex < text.length) {
            builder.append(text.substring(lastIndex))
        }

        val finalText = builder.toString()
        val mentionAndTagFacets = detectMentionsAndTags(finalText)
        return RichTextPayload(finalText, facets + mentionAndTagFacets)
    }

    /** Create a post on Bluesky */
    suspend fun createPost(request: NewPostRequest): ApiResult<NewPostResponse> {
        return try {
            // Validate request
            request.validate()?.let { error ->
                return error.left()
            }

            val session =
                when (val authResult = createSession()) {
                    is Either.Left -> return authResult.value.left()
                    is Either.Right -> authResult.value
                }

            val imageEmbeds =
                if (!request.images.isNullOrEmpty()) {
                    request.images.map { imageUuid ->
                        when (val uploadResult = uploadBlob(imageUuid, session)) {
                            is Either.Left -> return uploadResult.value.left()
                            is Either.Right -> uploadResult.value
                        }
                    }
                } else {
                    emptyList()
                }

            // Fetch link preview if link is present and no images
            // Note: Bluesky only supports one embed type at a time, and images take priority
            val linkPreview =
                if (request.link != null && imageEmbeds.isEmpty()) {
                    linkPreviewParser.fetchPreview(request.link)
                } else {
                    null
                }

            // Upload link preview image if present
            val linkPreviewBlobRef =
                if (linkPreview?.image != null) {
                    uploadBlobFromUrl(linkPreview.image, session)
                } else {
                    null
                }

            // Prepare text
            // If we have a link preview, use its canonical URL in the text
            // This ensures consistency between facets and external embed
            val finalLink =
                if (linkPreview != null && request.link != null) linkPreview.url else request.link
            val text =
                if (request.cleanupHtml == true) {
                    cleanupHtml(request.content)
                } else {
                    request.content.trim()
                } + if (finalLink != null) "\n\n$finalLink" else ""

            // Detect facets (mentions, links, hashtags) and shorten link display text
            val richText = buildRichText(text)

            logger.info { "Posting to Bluesky:\n${richText.text.trim().prependIndent("  |")}" }

            // Build post record
            val record = buildJsonObject {
                put("\$type", "app.bsky.feed.post")
                put("text", richText.text)
                put("createdAt", Instant.now().toString())

                if (richText.facets.isNotEmpty()) {
                    putJsonArray("facets") { richText.facets.forEach { add(it) } }
                }

                if (request.language != null) {
                    putJsonArray("langs") {
                        add(kotlinx.serialization.json.JsonPrimitive(request.language))
                    }
                }

                if (imageEmbeds.isNotEmpty()) {
                    putJsonObject("embed") {
                        put("\$type", "app.bsky.embed.images")
                        putJsonArray("images") {
                            imageEmbeds.forEach { embed ->
                                add(
                                    buildJsonObject {
                                        put("alt", embed.alt)
                                        putJsonObject("image") {
                                            put("\$type", embed.image.`$type`)
                                            put("ref", embed.image.ref)
                                            put("mimeType", embed.image.mimeType)
                                            put("size", embed.image.size)
                                        }
                                        embed.aspectRatio?.let { ratio ->
                                            putJsonObject("aspectRatio") {
                                                put("width", ratio.width)
                                                put("height", ratio.height)
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                } else if (linkPreview != null) {
                    // Add external link embed with preview
                    putJsonObject("embed") {
                        put("\$type", "app.bsky.embed.external")
                        putJsonObject("external") {
                            put("uri", linkPreview.url)
                            put("title", linkPreview.title)
                            put("description", linkPreview.description ?: "")
                            // Add image blob if available
                            if (linkPreviewBlobRef != null) {
                                putJsonObject("thumb") {
                                    put("\$type", linkPreviewBlobRef.`$type`)
                                    put("ref", linkPreviewBlobRef.ref)
                                    put("mimeType", linkPreviewBlobRef.mimeType)
                                    put("size", linkPreviewBlobRef.size)
                                }
                            }
                        }
                    }
                }
            }

            // Create the post
            val response =
                httpClient.post("${config.service}/xrpc/com.atproto.repo.createRecord") {
                    header("Authorization", "Bearer ${session.accessJwt}")
                    contentType(ContentType.Application.Json)
                    setBody(
                        buildJsonObject {
                            put("repo", session.did)
                            put("collection", "app.bsky.feed.post")
                            put("record", record)
                        }
                    )
                }

            if (response.status.value == 200) {
                val data = response.body<BlueskyPostResponse>()
                NewBlueSkyPostResponse(uri = data.uri, cid = data.cid).right()
            } else {
                val errorBody = response.bodyAsText()
                logger.warn { "Failed to post to Bluesky: ${response.status}, body: $errorBody" }
                RequestError(
                        status = response.status.value,
                        module = "bluesky",
                        errorMessage = "Failed to create post",
                        body = ResponseBody(asString = errorBody),
                    )
                    .left()
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to post to Bluesky" }
            CaughtException(
                    status = 500,
                    module = "bluesky",
                    errorMessage = "Failed to post to Bluesky: ${e.message}",
                )
                .left()
        }
    }

    /** Handle Bluesky post creation HTTP route */
    suspend fun createPostRoute(call: ApplicationCall) {
        val request =
            runCatching { call.receive<NewPostRequest>() }.getOrNull()
                ?: run {
                    val params = call.receiveParameters()
                    NewPostRequest(
                        content = params["content"] ?: "",
                        targets = params.getAll("targets"),
                        link = params["link"],
                        language = params["language"],
                        cleanupHtml = params["cleanupHtml"]?.toBoolean(),
                        images = params.getAll("images"),
                    )
                }

        when (val result = createPost(request)) {
            is Either.Right -> call.respond(result.value)
            is Either.Left -> {
                val error = result.value
                call.respond(
                    HttpStatusCode.fromValue(error.status),
                    ErrorResponse(error = error.errorMessage),
                )
            }
        }
    }

    private fun cleanupHtml(html: String): String {
        return html
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .trim()
    }
}
