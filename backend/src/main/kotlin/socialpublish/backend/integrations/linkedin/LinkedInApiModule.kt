package socialpublish.backend.integrations.linkedin

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import arrow.fx.coroutines.Resource
import arrow.fx.coroutines.resource
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import socialpublish.backend.models.ApiResult
import socialpublish.backend.models.CaughtException
import socialpublish.backend.models.ErrorResponse
import socialpublish.backend.models.NewLinkedInPostResponse
import socialpublish.backend.models.NewPostRequest
import socialpublish.backend.models.NewPostResponse
import socialpublish.backend.models.RequestError
import socialpublish.backend.models.ResponseBody
import socialpublish.backend.models.ValidationError
import socialpublish.backend.modules.FilesModule

private val logger = KotlinLogging.logger {}

@Serializable
data class LinkedInRegisterUploadRequest(val registerUploadRequest: RegisterUploadRequestData)

@Serializable
data class RegisterUploadRequestData(
    val owner: String,
    val recipes: List<String>,
    val serviceRelationships: List<ServiceRelationship>,
)

@Serializable data class ServiceRelationship(val identifier: String, val relationshipType: String)

@Serializable data class LinkedInRegisterUploadResponse(val value: RegisterUploadValue)

@Serializable
data class RegisterUploadValue(val asset: String, val uploadMechanism: UploadMechanism)

@Serializable
data class UploadMechanism(
    @SerialName("com.linkedin.digitalmedia.uploading.MediaUploadHttpRequest")
    val uploadRequest: UploadRequest
)

@Serializable
data class UploadRequest(val uploadUrl: String, val headers: Map<String, String>? = null)

@Serializable
data class LinkedInUGCPostRequest(
    val author: String,
    val lifecycleState: String,
    val specificContent: SpecificContent,
    val visibility: Visibility,
)

@Serializable
data class SpecificContent(
    @SerialName("com.linkedin.ugc.ShareContent") val shareContent: ShareContent
)

@Serializable
data class ShareContent(
    val shareCommentary: ShareCommentary,
    val shareMediaCategory: String,
    val media: List<ShareMedia>? = null,
)

@Serializable data class ShareCommentary(val text: String)

@Serializable
data class ShareMedia(
    val status: String,
    val description: Description? = null,
    val media: String,
    val title: Title? = null,
    val originalUrl: String? = null,
)

@Serializable data class Description(val text: String)

@Serializable data class Title(val text: String)

@Serializable
data class Visibility(
    @SerialName("com.linkedin.ugc.MemberNetworkVisibility") val memberNetworkVisibility: String
)

@Serializable data class LinkedInUGCPostResponse(val id: String)

class LinkedInApiModule(
    private val config: LinkedInConfig,
    private val filesModule: FilesModule,
    private val httpClientEngine: HttpClientEngine,
) {
    private val httpClient: HttpClient by lazy {
        HttpClient(httpClientEngine) {
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                    }
                )
            }
        }
    }

    companion object {
        fun resource(
            config: LinkedInConfig,
            filesModule: FilesModule,
        ): Resource<LinkedInApiModule> = resource {
            val engine = install({ CIO.create() }) { engine, _ -> engine.close() }
            LinkedInApiModule(config, filesModule, engine)
        }
    }

    /** Upload media to LinkedIn */
    private suspend fun uploadMedia(uuid: String): ApiResult<String> {
        return try {
            val file =
                filesModule.readImageFile(uuid, maxWidth = 5000, maxHeight = 5000)
                    ?: return ValidationError(
                            status = 404,
                            errorMessage = "Failed to read image file — uuid: $uuid",
                            module = "linkedin",
                        )
                        .left()

            // Step 1: Register upload
            val registerRequest =
                LinkedInRegisterUploadRequest(
                    registerUploadRequest =
                        RegisterUploadRequestData(
                            owner = config.personUrn,
                            recipes = listOf("urn:li:digitalmediaRecipe:feedshare-image"),
                            serviceRelationships =
                                listOf(
                                    ServiceRelationship(
                                        identifier = "urn:li:userGeneratedContent",
                                        relationshipType = "OWNER",
                                    )
                                ),
                        )
                )

            val registerResponse =
                httpClient.post("${config.apiBase}/assets?action=registerUpload") {
                    header("Authorization", "Bearer ${config.accessToken}")
                    header("X-Restli-Protocol-Version", "2.0.0")
                    contentType(ContentType.Application.Json)
                    setBody(registerRequest)
                }

            if (registerResponse.status != HttpStatusCode.OK) {
                val errorBody = registerResponse.bodyAsText()
                logger.warn {
                    "Failed to register upload on LinkedIn: ${registerResponse.status}, body: $errorBody"
                }
                return RequestError(
                        status = registerResponse.status.value,
                        module = "linkedin",
                        errorMessage = "Failed to register upload",
                        body = ResponseBody(asString = errorBody),
                    )
                    .left()
            }

            val registerData = registerResponse.body<LinkedInRegisterUploadResponse>()
            val uploadUrl = registerData.value.uploadMechanism.uploadRequest.uploadUrl
            val asset = registerData.value.asset

            // Step 2: Upload the binary
            val uploadBinaryResponse =
                httpClient.put(uploadUrl) {
                    header("Authorization", "Bearer ${config.accessToken}")
                    contentType(ContentType.parse(file.mimetype))
                    setBody(file.bytes)
                }

            if (uploadBinaryResponse.status !in listOf(HttpStatusCode.OK, HttpStatusCode.Created)) {
                val errorBody = uploadBinaryResponse.bodyAsText()
                logger.warn {
                    "Failed to upload binary to LinkedIn: ${uploadBinaryResponse.status}, body: $errorBody"
                }
                return RequestError(
                        status = uploadBinaryResponse.status.value,
                        module = "linkedin",
                        errorMessage = "Failed to upload binary",
                        body = ResponseBody(asString = errorBody),
                    )
                    .left()
            }

            asset.right()
        } catch (e: Exception) {
            logger.error(e) { "Failed to upload media to LinkedIn — uuid $uuid" }
            CaughtException(
                    status = 500,
                    module = "linkedin",
                    errorMessage = "Failed to upload media — uuid: $uuid",
                )
                .left()
        }
    }

    /** Fetch link preview metadata using og:tags */
    private suspend fun fetchLinkPreview(url: String): Pair<String?, String?> {
        return try {
            val doc = Jsoup.connect(url).get()
            val title =
                doc.select("meta[property=og:title]").attr("content").ifEmpty { doc.title() }
            val imageUrl = doc.select("meta[property=og:image]").attr("content").ifEmpty { null }
            Pair(title.ifEmpty { null }, imageUrl)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to fetch link preview for $url" }
            Pair(null, null)
        }
    }

    /** Create a post on LinkedIn */
    suspend fun createPost(request: NewPostRequest): ApiResult<NewPostResponse> {
        return try {
            // Validate request
            request.validate()?.let { error ->
                return error.left()
            }

            // Prepare text content
            val content =
                if (request.cleanupHtml == true) {
                    cleanupHtml(request.content)
                } else {
                    request.content
                }

            logger.info { "Posting to LinkedIn:\n${content.trim().prependIndent("  |")}" }

            // Build media list
            val mediaList = mutableListOf<ShareMedia>()

            // Upload images if present
            if (!request.images.isNullOrEmpty()) {
                for (imageUuid in request.images) {
                    when (val result = uploadMedia(imageUuid)) {
                        is Either.Right -> {
                            mediaList.add(ShareMedia(status = "READY", media = result.value))
                        }
                        is Either.Left -> return result.value.left()
                    }
                }
            }

            // Add link preview if link is provided
            if (request.link != null && mediaList.isEmpty()) {
                val (title, imageUrl) = fetchLinkPreview(request.link)
                // Only add media if we have an image URL, otherwise LinkedIn will reject it
                if (imageUrl != null && imageUrl.isNotEmpty()) {
                    mediaList.add(
                        ShareMedia(
                            status = "READY",
                            originalUrl = request.link,
                            title = title?.let { Title(text = it) },
                            description = Description(text = content),
                            media = imageUrl,
                        )
                    )
                }
            }

            // Determine share media category
            val shareMediaCategory =
                when {
                    mediaList.isEmpty() -> "NONE"
                    request.link != null && request.images.isNullOrEmpty() -> "ARTICLE"
                    else -> "IMAGE"
                }

            // Create post
            val postRequest =
                LinkedInUGCPostRequest(
                    author = config.personUrn,
                    lifecycleState = "PUBLISHED",
                    specificContent =
                        SpecificContent(
                            shareContent =
                                ShareContent(
                                    shareCommentary = ShareCommentary(text = content),
                                    shareMediaCategory = shareMediaCategory,
                                    media = mediaList.ifEmpty { null },
                                )
                        ),
                    visibility = Visibility(memberNetworkVisibility = "PUBLIC"),
                )

            val response =
                httpClient.post("${config.apiBase}/ugcPosts") {
                    header("Authorization", "Bearer ${config.accessToken}")
                    header("X-Restli-Protocol-Version", "2.0.0")
                    contentType(ContentType.Application.Json)
                    setBody(postRequest)
                }

            if (response.status == HttpStatusCode.Created) {
                val data = response.body<LinkedInUGCPostResponse>()
                NewLinkedInPostResponse(postId = data.id).right()
            } else {
                val errorBody = response.bodyAsText()
                logger.warn { "Failed to post to LinkedIn: ${response.status}, body: $errorBody" }
                RequestError(
                        status = response.status.value,
                        module = "linkedin",
                        errorMessage = "Failed to create post",
                        body = ResponseBody(asString = errorBody),
                    )
                    .left()
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to post to LinkedIn" }
            CaughtException(
                    status = 500,
                    module = "linkedin",
                    errorMessage = "Failed to post to LinkedIn: ${e.message}",
                )
                .left()
        }
    }

    /** Handle LinkedIn post creation HTTP route */
    suspend fun createPostRoute(call: ApplicationCall) {
        val request =
            runCatching { call.receive<NewPostRequest>() }.getOrNull()
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
