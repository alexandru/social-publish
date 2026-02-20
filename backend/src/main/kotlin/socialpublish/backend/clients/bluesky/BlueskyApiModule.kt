package socialpublish.backend.clients.bluesky

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import arrow.fx.coroutines.Resource
import arrow.fx.coroutines.resource
import arrow.fx.coroutines.resourceScope
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
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
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.asSource
import java.time.Instant
import java.util.UUID
import kotlinx.io.buffered
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import socialpublish.backend.clients.linkpreview.LinkPreviewParser
import socialpublish.backend.common.*
import socialpublish.backend.modules.FilesModule
import socialpublish.backend.modules.UploadedFile

private val logger = KotlinLogging.logger {}

private const val BlueskyLinkDisplayLength = 24

data class BlueskyReplyContext(
    val rootUri: String,
    val rootCid: String,
    val parentUri: String,
    val parentCid: String,
)

/** Bluesky API module implementing AT Protocol */
class BlueskyApiModule(
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

        fun resource(filesModule: FilesModule): Resource<BlueskyApiModule> = resource {
            BlueskyApiModule(
                filesModule = filesModule,
                linkPreviewParser = LinkPreviewParser().bind(),
                httpClient = defaultHttpClient().bind(),
            )
        }
    }

    /** Login to Bluesky and get session token */
    private suspend fun createSession(config: BlueskyConfig): ApiResult<BlueskySessionResponse> {
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
        config: BlueskyConfig,
        uuid: String,
        session: BlueskySessionResponse,
        userUuid: UUID,
    ): ApiResult<BlueskyImageEmbed> = resourceScope {
        try {
            val file =
                filesModule.readImageFile(uuid, userUuid)
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
                        size = file.size,
                    )

                return@resourceScope BlueskyImageEmbed(
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
        config: BlueskyConfig,
        imageUrl: String,
        session: BlueskySessionResponse,
    ): BlueskyBlobRef? = resourceScope {
        try {
            // Fetch the image
            val response = httpClient.get(imageUrl)
            if (response.status.value != 200) {
                logger.warn { "Failed to fetch image from $imageUrl: ${response.status}" }
                return@resourceScope null
            }

            val fileName = imageUrl.substringAfterLast('/').takeIf { it.isNotBlank() } ?: "image"
            val imageSource =
                UploadSource.FromSource(response.body<ByteReadChannel>().asSource().buffered())
            val uploadedFile =
                filesModule
                    .processFile(
                        UploadedFile(fileName = fileName, source = imageSource, altText = null)
                    )
                    .bind()
                    .getOrElse { error ->
                        logger.warn {
                            "Failed to upload image from URL $imageUrl to temp storage: ${error.toJsonString()}"
                        }
                        return@resourceScope null
                    }

            logger.info {
                "Fetched image from $imageUrl: ${uploadedFile.mimetype}, ${uploadedFile.size} bytes"
            }

            // Upload to Bluesky
            val uploadResponse =
                httpClient.post("${config.service}/xrpc/com.atproto.repo.uploadBlob") {
                    header("Authorization", "Bearer ${session.accessJwt}")
                    contentType(ContentType.parse(uploadedFile.mimetype))
                    setBody(ByteReadChannel(uploadedFile.source.asKotlinSource().bind()))
                }

            if (uploadResponse.status.isSuccess()) {
                val blobData = uploadResponse.body<JsonObject>()
                val blob = blobData["blob"] as? JsonObject ?: return null

                return@resourceScope BlueskyBlobRef(
                    `$type` = "blob",
                    ref = blob["ref"] as JsonObject,
                    mimeType = uploadedFile.mimetype,
                    size = uploadedFile.size,
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
    private suspend fun resolveHandle(config: BlueskyConfig, handle: String): String? {
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
        assert(maxLength > 3) { "maxLength must be greater than 3" }
        val cleanUrl = url.replace("^https?://".toRegex(), "")
        return if (cleanUrl.length <= maxLength) {
            cleanUrl
        } else {
            cleanUrl.take(maxLength - 3) + "..."
        }
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
    private suspend fun detectMentionsAndTags(
        config: BlueskyConfig,
        text: String,
    ): List<JsonObject> {
        val facets = mutableListOf<JsonObject>()

        // 1. Detect Mentions (@handle.bsky.social)
        val mentionRegex = Regex("""(?<=\s|^)(@[a-zA-Z0-9.-]+)""")
        for (match in mentionRegex.findAll(text)) {
            val handle = match.value.substring(1) // Remove '@'
            // Only resolve if it looks like a valid handle (has at least one dot)
            if (handle.contains(".")) {
                val did = resolveHandle(config, handle)
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
    private suspend fun buildRichText(config: BlueskyConfig, text: String): RichTextPayload {
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
        val mentionAndTagFacets = detectMentionsAndTags(config, finalText)
        return RichTextPayload(finalText, facets + mentionAndTagFacets)
    }

    /** Create a post on Bluesky */
    suspend fun createPost(
        config: BlueskyConfig,
        request: NewPostRequest,
        userUuid: UUID,
        replyContext: BlueskyReplyContext? = null,
    ): ApiResult<NewPostResponse> {
        return try {
            // Validate request
            request.validate()?.let { error ->
                return error.left()
            }

            val message = request.messages.first()

            val session =
                when (val authResult = createSession(config)) {
                    is Either.Left -> return authResult.value.left()
                    is Either.Right -> authResult.value
                }

            val imageEmbeds =
                if (!message.images.isNullOrEmpty()) {
                    message.images.map { imageUuid ->
                        when (val uploadResult = uploadBlob(config, imageUuid, session, userUuid)) {
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
                if (message.link != null && imageEmbeds.isEmpty()) {
                    linkPreviewParser.fetchPreview(message.link)
                } else {
                    null
                }

            // Upload link preview image if present
            val linkPreviewBlobRef =
                if (linkPreview?.image != null) {
                    uploadBlobFromUrl(config, linkPreview.image, session)
                } else {
                    null
                }

            // Prepare text
            // If we have a link preview, use its canonical URL in the text
            // This ensures consistency between facets and external embed
            val url = message.link
            val text = message.content.trim() + if (url != null) "\n\n$url" else ""

            // Detect facets (mentions, links, hashtags) and shorten link display text
            val richText = buildRichText(config, text)

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
                    putJsonArray("langs") { add(JsonPrimitive(request.language)) }
                }

                replyContext?.let { reply ->
                    putJsonObject("reply") {
                        putJsonObject("root") {
                            put("uri", reply.rootUri)
                            put("cid", reply.rootCid)
                        }
                        putJsonObject("parent") {
                            put("uri", reply.parentUri)
                            put("cid", reply.parentCid)
                        }
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
                            put("uri", url)
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
                NewBlueSkyPostResponse(
                        uri = data.uri,
                        cid = data.cid,
                        messages =
                            listOf(
                                PublishedMessageResponse(
                                    id = data.uri,
                                    uri = data.uri,
                                    replyToId = replyContext?.parentUri,
                                )
                            ),
                    )
                    .right()
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

    suspend fun createThread(
        config: BlueskyConfig,
        request: NewPostRequest,
        userUuid: UUID,
    ): ApiResult<NewPostResponse> {
        request.validate()?.let {
            return it.left()
        }

        var rootUri: String? = null
        var rootCid: String? = null
        var parentUri: String? = null
        var parentCid: String? = null
        val messages = mutableListOf<PublishedMessageResponse>()

        for (message in request.messages) {
            val replyContext =
                if (rootUri != null && rootCid != null && parentUri != null && parentCid != null) {
                    BlueskyReplyContext(rootUri, rootCid, parentUri, parentCid)
                } else {
                    null
                }

            val singleRequest =
                NewPostRequest(
                    targets = request.targets,
                    language = request.language,
                    messages = listOf(message),
                )

            when (
                val result =
                    createPost(
                        config = config,
                        request = singleRequest,
                        userUuid = userUuid,
                        replyContext = replyContext,
                    )
            ) {
                is Either.Left -> return result.value.left()
                is Either.Right -> {
                    val response = result.value as NewBlueSkyPostResponse
                    if (rootUri == null || rootCid == null) {
                        rootUri = response.uri
                        rootCid =
                            response.cid
                                ?: return ValidationError(
                                        status = 500,
                                        module = "bluesky",
                                        errorMessage = "Missing cid for root post",
                                    )
                                    .left()
                    }
                    parentUri = response.uri
                    parentCid =
                        response.cid
                            ?: return ValidationError(
                                    status = 500,
                                    module = "bluesky",
                                    errorMessage = "Missing cid for reply post",
                                )
                                .left()
                    messages += response.messages.first()
                }
            }
        }

        val firstMessage = messages.firstOrNull()
        return NewBlueSkyPostResponse(
                uri = firstMessage?.uri ?: "",
                cid = rootCid,
                messages = messages,
            )
            .right()
    }

    /** Handle Bluesky post creation HTTP route */
    suspend fun createPostRoute(call: ApplicationCall, config: BlueskyConfig, userUuid: UUID) {
        val request =
            runCatching { call.receive<NewPostRequest>() }.getOrNull()
                ?: run {
                    val params = call.receiveParameters()
                    NewPostRequest(
                        targets = params.getAll("targets"),
                        language = params["language"],
                        messages =
                            listOf(
                                NewPostRequestMessage(
                                    content = params["content"] ?: "",
                                    link = params["link"],
                                    images = params.getAll("images"),
                                )
                            ),
                    )
                }

        when (val result = createThread(config, request, userUuid)) {
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
}
