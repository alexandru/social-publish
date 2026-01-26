package socialpublish.backend.clients.bluesky

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import arrow.fx.coroutines.Resource
import arrow.fx.coroutines.resource
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
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import socialpublish.backend.clients.linkpreview.LinkPreviewParser
import socialpublish.backend.models.ApiResult
import socialpublish.backend.models.CaughtException
import socialpublish.backend.models.ErrorResponse
import socialpublish.backend.models.NewBlueSkyPostResponse
import socialpublish.backend.models.NewPostRequest
import socialpublish.backend.models.NewPostResponse
import socialpublish.backend.models.RequestError
import socialpublish.backend.models.ResponseBody
import socialpublish.backend.models.ValidationError
import socialpublish.backend.modules.FilesModule

private val logger = KotlinLogging.logger {}

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

    private var accessToken: String? = null
    private var sessionDid: String? = null

    /** Login to Bluesky and get session token */
    private suspend fun ensureAuthenticated(): ApiResult<Unit> {
        if (accessToken != null) {
            return Unit.right()
        }

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
                accessToken = session.accessJwt
                sessionDid = session.did
                logger.info { "Authenticated to Bluesky as ${session.handle}" }
                Unit.right()
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
    private suspend fun uploadBlob(uuid: String): ApiResult<BlueskyImageEmbed> {
        return try {
            val file =
                filesModule.readImageFile(uuid)
                    ?: return ValidationError(
                            status = 404,
                            errorMessage = "Failed to read image file — uuid: $uuid",
                            module = "bluesky",
                        )
                        .left()

            when (val authResult = ensureAuthenticated()) {
                is Either.Left -> return authResult.value.left()
                is Either.Right -> {}
            }

            val response =
                httpClient.post("${config.service}/xrpc/com.atproto.repo.uploadBlob") {
                    header("Authorization", "Bearer $accessToken")
                    contentType(ContentType.parse(file.mimetype))
                    setBody(file.bytes)
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

                BlueskyImageEmbed(
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
            } else {
                val errorBody = response.bodyAsText()
                logger.warn {
                    "Failed to upload blob to Bluesky: ${response.status}, body: $errorBody"
                }
                RequestError(
                        status = response.status.value,
                        module = "bluesky",
                        errorMessage = "Failed to upload blob",
                        body = ResponseBody(asString = errorBody),
                    )
                    .left()
            }
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
    private suspend fun uploadBlobFromUrl(imageUrl: String): BlueskyBlobRef? {
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
            when (ensureAuthenticated()) {
                is Either.Left -> return null
                is Either.Right -> {}
            }

            val uploadResponse =
                httpClient.post("${config.service}/xrpc/com.atproto.repo.uploadBlob") {
                    header("Authorization", "Bearer $accessToken")
                    contentType(ContentType.parse(contentType))
                    setBody(imageBytes)
                }

            if (uploadResponse.status.isSuccess()) {
                val blobData = uploadResponse.body<JsonObject>()
                val blob = blobData["blob"] as? JsonObject ?: return null

                BlueskyBlobRef(
                    `$type` = "blob",
                    ref = blob["ref"] as JsonObject,
                    mimeType = contentType,
                    size = imageBytes.size,
                )
            } else {
                logger.warn {
                    "Failed to upload blob from URL to Bluesky: ${uploadResponse.status}"
                }
                null
            }
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

    /**
     * Detect facets (mentions, links, hashtags) in text Supports:
     * - URLs (app.bsky.richtext.facet#link)
     * - Mentions @handle (app.bsky.richtext.facet#mention)
     * - Hashtags #tag (app.bsky.richtext.facet#tag)
     *
     * IMPORTANT: Calculates byte offsets using UTF-8 to match AT Protocol spec.
     */
    private suspend fun detectFacets(text: String): List<JsonObject> {
        val facets = mutableListOf<JsonObject>()

        // 1. Detect URLs
        // Regex handles http/https and ensures valid surrounding characters
        val urlRegex = Regex("""(?<=\s|^)(https?://[^\s]+)""")
        urlRegex.findAll(text).forEach { match ->
            val byteStart = text.substring(0, match.range.first).toByteArray(Charsets.UTF_8).size
            val byteEnd = byteStart + match.value.toByteArray(Charsets.UTF_8).size

            val facet = buildJsonObject {
                putJsonObject("index") {
                    put("byteStart", byteStart)
                    put("byteEnd", byteEnd)
                }
                putJsonArray("features") {
                    add(
                        buildJsonObject {
                            put("\$type", "app.bsky.richtext.facet#link")
                            put("uri", match.value)
                        }
                    )
                }
            }
            facets.add(facet)
        }

        // 2. Detect Mentions (@handle.bsky.social)
        val mentionRegex = Regex("""(?<=\s|^)(@[a-zA-Z0-9.-]+)""")
        for (match in mentionRegex.findAll(text)) {
            val handle = match.value.substring(1) // Remove '@'
            // Only resolve if it looks like a valid handle (has at least one dot)
            if (handle.contains(".")) {
                val did = resolveHandle(handle)
                if (did != null) {
                    val byteStart =
                        text.substring(0, match.range.first).toByteArray(Charsets.UTF_8).size
                    val byteEnd = byteStart + match.value.toByteArray(Charsets.UTF_8).size

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

        // 3. Detect Hashtags (#tag)
        val tagRegex = Regex("""(?<=\s|^)(#[a-zA-Z0-9]+)""")
        tagRegex.findAll(text).forEach { match ->
            val tag = match.value.substring(1) // Remove '#'
            val byteStart = text.substring(0, match.range.first).toByteArray(Charsets.UTF_8).size
            val byteEnd = byteStart + match.value.toByteArray(Charsets.UTF_8).size

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

    /** Create a post on Bluesky */
    suspend fun createPost(request: NewPostRequest): ApiResult<NewPostResponse> {
        return try {
            // Validate request
            request.validate()?.let { error ->
                return error.left()
            }

            // Ensure authenticated
            when (val authResult = ensureAuthenticated()) {
                is Either.Left -> return authResult.value.left()
                is Either.Right -> {}
            }

            // Upload images if present
            val imageEmbeds = mutableListOf<BlueskyImageEmbed>()
            if (!request.images.isNullOrEmpty()) {
                for (imageUuid in request.images) {
                    when (val result = uploadBlob(imageUuid)) {
                        is Either.Right -> imageEmbeds.add(result.value)
                        is Either.Left -> return result.value.left()
                    }
                }
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
                    uploadBlobFromUrl(linkPreview.image)
                } else {
                    null
                }

            // Prepare text
            val text =
                if (request.cleanupHtml == true) {
                    cleanupHtml(request.content)
                } else {
                    request.content.trim()
                } + if (request.link != null) "\n\n${request.link}" else ""

            logger.info { "Posting to Bluesky:\n${text.trim().prependIndent("  |")}" }

            // Detect facets (mentions, links, hashtags)
            val facets = detectFacets(text)

            // Build post record
            val record = buildJsonObject {
                put("\$type", "app.bsky.feed.post")
                put("text", text)
                put("createdAt", Instant.now().toString())

                if (facets.isNotEmpty()) {
                    putJsonArray("facets") { facets.forEach { add(it) } }
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
                    header("Authorization", "Bearer $accessToken")
                    contentType(ContentType.Application.Json)
                    setBody(
                        buildJsonObject {
                            put("repo", sessionDid)
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
