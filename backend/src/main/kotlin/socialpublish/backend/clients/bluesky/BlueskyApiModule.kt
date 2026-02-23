package socialpublish.backend.clients.bluesky

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.nonEmptyListOf
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
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.asSource
import java.text.BreakIterator
import java.time.Instant
import java.util.UUID
import kotlinx.io.buffered
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import socialpublish.backend.clients.common.SocialMediaApi
import socialpublish.backend.clients.linkpreview.LinkPreviewParser
import socialpublish.backend.common.*
import socialpublish.backend.modules.FilesModule
import socialpublish.backend.modules.UploadedFile

private val logger = KotlinLogging.logger {}

private const val BlueskyLinkDisplayLength = 24
private const val BlueskyCharacterLimit = 300
private const val LinkLength = 25

private data class BlueskyReplyContext(
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
) : SocialMediaApi<BlueskyConfig> {
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
                        BlueskyCreateSessionRequest(
                            identifier = config.username,
                            password = config.password,
                        )
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
                val blob = response.body<BlueskyBlobUploadResponse>().blob
                val blobRef =
                    BlueskyBlobRef(
                        `$type` = "blob",
                        ref = blob.ref,
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
                val blob = uploadResponse.body<BlueskyBlobUploadResponse>().blob

                return@resourceScope BlueskyBlobRef(
                    `$type` = "blob",
                    ref = blob.ref,
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

    private fun countGraphemes(text: String): Int {
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(text)
        var count = 0
        var index = iterator.first()
        while (index != BreakIterator.DONE) {
            val next = iterator.next()
            if (next == BreakIterator.DONE) {
                break
            }
            count += 1
            index = next
        }
        return count
    }

    override fun validateRequest(request: NewPostRequest): ValidationError? {
        val urlRegex = Regex("(https?://\\S+)")
        request.messages.forEach { message ->
            if (message.content.isEmpty()) {
                return ValidationError(
                    status = 400,
                    module = "bluesky",
                    errorMessage = "Content must not be empty",
                )
            }
            val text =
                message.content.trim() + if (message.link != null) "\n\n${message.link}" else ""
            val links = urlRegex.findAll(text).count()
            val textWithoutLinks = urlRegex.replace(text, "")
            val length = countGraphemes(textWithoutLinks) + (links * LinkLength)
            if (length > BlueskyCharacterLimit) {
                return ValidationError(
                    status = 400,
                    module = "bluesky",
                    errorMessage = "Bluesky post exceeds $BlueskyCharacterLimit characters",
                )
            }
        }
        return null
    }

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

    private data class RichTextPayload(val text: String, val facets: List<BlueskyFacet>)

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

    private fun buildLinkFacet(byteStart: Int, byteEnd: Int, uri: String): BlueskyFacet =
        BlueskyFacet(
            index = BlueskyFacetIndex(byteStart = byteStart, byteEnd = byteEnd),
            features =
                listOf(BlueskyFacetFeature(`$type` = "app.bsky.richtext.facet#link", uri = uri)),
        )

    /**
     * Detect facets (mentions, hashtags) in text.
     *
     * IMPORTANT: Calculates byte offsets using UTF-8 to match AT Protocol spec.
     */
    private suspend fun detectMentionsAndTags(
        config: BlueskyConfig,
        text: String,
    ): List<BlueskyFacet> {
        val facets = mutableListOf<BlueskyFacet>()

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

                    val facet =
                        BlueskyFacet(
                            index = BlueskyFacetIndex(byteStart = byteStart, byteEnd = byteEnd),
                            features =
                                listOf(
                                    BlueskyFacetFeature(
                                        `$type` = "app.bsky.richtext.facet#mention",
                                        did = did,
                                    )
                                ),
                        )
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

            val facet =
                BlueskyFacet(
                    index = BlueskyFacetIndex(byteStart = byteStart, byteEnd = byteEnd),
                    features =
                        listOf(
                            BlueskyFacetFeature(`$type` = "app.bsky.richtext.facet#tag", tag = tag)
                        ),
                )
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
        val facets = mutableListOf<BlueskyFacet>()
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
    ): ApiResult<NewPostResponse> {
        return createPost(config, request, userUuid, replyContext = null)
    }

    private suspend fun createPost(
        config: BlueskyConfig,
        request: NewPostRequest,
        userUuid: UUID,
        replyContext: BlueskyReplyContext?,
    ): ApiResult<NewPostResponse> {
        return try {
            validateRequest(request)?.let { error ->
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

            val embed =
                when {
                    imageEmbeds.isNotEmpty() ->
                        Json.encodeToJsonElement(
                            BlueskyImagesEmbed.serializer(),
                            BlueskyImagesEmbed(
                                `$type` = "app.bsky.embed.images",
                                images = imageEmbeds,
                            ),
                        )
                    linkPreview != null && url != null ->
                        Json.encodeToJsonElement(
                            BlueskyExternalEmbed.serializer(),
                            BlueskyExternalEmbed(
                                `$type` = "app.bsky.embed.external",
                                external =
                                    BlueskyExternal(
                                        uri = url,
                                        title = linkPreview.title,
                                        description = linkPreview.description ?: "",
                                        thumb = linkPreviewBlobRef,
                                    ),
                            ),
                        )
                    else -> null
                }

            val record =
                BlueskyPostRecord(
                    `$type` = "app.bsky.feed.post",
                    text = richText.text,
                    createdAt = Instant.now().toString(),
                    facets = richText.facets.takeIf { it.isNotEmpty() },
                    langs = request.language?.let { listOf(it) },
                    reply =
                        replyContext?.let { reply ->
                            BlueskyReply(
                                root = BlueskyReplyRef(uri = reply.rootUri, cid = reply.rootCid),
                                parent =
                                    BlueskyReplyRef(uri = reply.parentUri, cid = reply.parentCid),
                            )
                        },
                    embed = embed,
                )

            // Create the post
            val response =
                httpClient.post("${config.service}/xrpc/com.atproto.repo.createRecord") {
                    header("Authorization", "Bearer ${session.accessJwt}")
                    contentType(ContentType.Application.Json)
                    setBody(
                        BlueskyCreateRecordRequest(
                            repo = session.did,
                            collection = "app.bsky.feed.post",
                            record = record,
                        )
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

    override suspend fun createThread(
        config: BlueskyConfig,
        request: NewPostRequest,
        userUuid: UUID,
    ): ApiResult<NewPostResponse> {
        validateRequest(request)?.let {
            return it.left()
        }

        var replyContext: BlueskyReplyContext? = null
        val messages = mutableListOf<PublishedMessageResponse>()
        var rootUri = ""
        var rootCid = ""

        for (message in request.messages) {
            val singleRequest =
                NewPostRequest(
                    targets = request.targets,
                    language = request.language,
                    messages = nonEmptyListOf(message),
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
                    if (messages.isEmpty()) {
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
                    val parentCid =
                        response.cid
                            ?: return ValidationError(
                                    status = 500,
                                    module = "bluesky",
                                    errorMessage = "Missing cid for reply post",
                                )
                                .left()
                    val published = response.messages.first()
                    messages += published
                    replyContext =
                        BlueskyReplyContext(
                            rootUri = rootUri,
                            rootCid = rootCid,
                            parentUri = response.uri,
                            parentCid = parentCid,
                        )
                }
            }
        }

        return NewBlueSkyPostResponse(uri = rootUri, cid = rootCid, messages = messages).right()
    }
}
