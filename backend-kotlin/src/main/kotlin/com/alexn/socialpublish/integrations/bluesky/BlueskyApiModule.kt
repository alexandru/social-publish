package com.alexn.socialpublish.integrations.bluesky

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.alexn.socialpublish.models.ApiResult
import com.alexn.socialpublish.models.CaughtException
import com.alexn.socialpublish.models.NewBlueSkyPostResponse
import com.alexn.socialpublish.models.NewPostRequest
import com.alexn.socialpublish.models.NewPostResponse
import com.alexn.socialpublish.models.RequestError
import com.alexn.socialpublish.models.ResponseBody
import com.alexn.socialpublish.models.ValidationError
import com.alexn.socialpublish.modules.FilesModule
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant

private val logger = KotlinLogging.logger {}

@Serializable
data class BlueskySessionResponse(
    val accessJwt: String,
    val refreshJwt: String,
    val handle: String,
    val did: String,
)

@Serializable
data class BlueskyBlobRef(
    val `$type`: String = "blob",
    val ref: JsonObject,
    val mimeType: String,
    val size: Int,
)

@Serializable
data class BlueskyImageEmbed(
    val alt: String,
    val image: BlueskyBlobRef,
    val aspectRatio: BlueskyAspectRatio? = null,
)

@Serializable
data class BlueskyAspectRatio(
    val width: Int,
    val height: Int,
)

@Serializable
data class BlueskyPostResponse(
    val uri: String,
    val cid: String,
)

/**
 * Bluesky API module implementing AT Protocol
 */
class BlueskyApiModule(
    private val config: BlueskyConfig,
    private val filesModule: FilesModule,
) {
    private val httpClient =
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                    },
                )
            }
        }

    private var accessToken: String? = null
    private var sessionDid: String? = null

    /**
     * Login to Bluesky and get session token
     */
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
                        },
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
                logger.warn { "Failed to authenticate to Bluesky: ${response.status}, body: $errorBody" }
                RequestError(
                    status = response.status.value,
                    module = "bluesky",
                    errorMessage = "Failed to authenticate to Bluesky",
                    body = ResponseBody(asString = errorBody),
                ).left()
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to authenticate to Bluesky" }
            CaughtException(
                status = 500,
                module = "bluesky",
                errorMessage = "Failed to authenticate to Bluesky: ${e.message}",
            ).left()
        }
    }

    /**
     * Upload image blob to Bluesky
     */
    private suspend fun uploadBlob(uuid: String): ApiResult<BlueskyImageEmbed> {
        return try {
            val file =
                filesModule.readImageFile(uuid)
                    ?: return ValidationError(
                        status = 404,
                        errorMessage = "Failed to read image file — uuid: $uuid",
                        module = "bluesky",
                    ).left()

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
                        ).left()

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
                ).right()
            } else {
                val errorBody = response.bodyAsText()
                logger.warn { "Failed to upload blob to Bluesky: ${response.status}, body: $errorBody" }
                RequestError(
                    status = response.status.value,
                    module = "bluesky",
                    errorMessage = "Failed to upload blob",
                    body = ResponseBody(asString = errorBody),
                ).left()
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to upload blob (bluesky) — uuid $uuid" }
            CaughtException(
                status = 500,
                module = "bluesky",
                errorMessage = "Failed to upload blob — uuid: $uuid",
            ).left()
        }
    }

    /**
     * Detect facets (mentions, links, hashtags) in text
     * Simplified version - just detects URLs
     */
    private fun detectFacets(text: String): List<JsonObject> {
        val facets = mutableListOf<JsonObject>()
        val urlRegex = Regex("""https?://[^\s]+""")

        urlRegex.findAll(text).forEach { match ->
            val facet =
                buildJsonObject {
                    putJsonObject("index") {
                        put("byteStart", match.range.first)
                        put("byteEnd", match.range.last + 1)
                    }
                    putJsonArray("features") {
                        add(
                            buildJsonObject {
                                put("\$type", "app.bsky.richtext.facet#link")
                                put("uri", match.value)
                            },
                        )
                    }
                }
            facets.add(facet)
        }

        return facets
    }

    /**
     * Create a post on Bluesky
     */
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
            val record =
                buildJsonObject {
                    put("\$type", "app.bsky.feed.post")
                    put("text", text)
                    put("createdAt", Instant.now().toString())

                    if (facets.isNotEmpty()) {
                        putJsonArray("facets") {
                            facets.forEach { add(it) }
                        }
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
                                        },
                                    )
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
                        },
                    )
                }

            if (response.status.value == 200) {
                val data = response.body<BlueskyPostResponse>()
                NewBlueSkyPostResponse(
                    uri = data.uri,
                    cid = data.cid,
                ).right()
            } else {
                val errorBody = response.bodyAsText()
                logger.warn { "Failed to post to Bluesky: ${response.status}, body: $errorBody" }
                RequestError(
                    status = response.status.value,
                    module = "bluesky",
                    errorMessage = "Failed to create post",
                    body = ResponseBody(asString = errorBody),
                ).left()
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to post to Bluesky" }
            CaughtException(
                status = 500,
                module = "bluesky",
                errorMessage = "Failed to post to Bluesky: ${e.message}",
            ).left()
        }
    }

    /**
     * Handle Bluesky post creation HTTP route
     */
    suspend fun createPostRoute(call: ApplicationCall) {
        val request =
            runCatching {
                call.receive<NewPostRequest>()
            }.getOrNull()
                ?: run {
                    val params = call.receiveParameters()
                    NewPostRequest(
                        content = params["content"] ?: "",
                        targets = params.getAll("targets[]"),
                        link = params["link"],
                        language = params["language"],
                        cleanupHtml = params["cleanupHtml"]?.toBoolean(),
                        images = params.getAll("images[]"),
                    )
                }

        when (val result = createPost(request)) {
            is Either.Right -> call.respond(result.value)
            is Either.Left -> {
                val error = result.value
                call.respond(HttpStatusCode.fromValue(error.status), mapOf("error" to error.errorMessage))
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
