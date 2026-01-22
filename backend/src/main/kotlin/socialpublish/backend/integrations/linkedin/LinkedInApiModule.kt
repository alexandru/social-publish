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
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import java.net.URLEncoder
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import socialpublish.backend.db.DocumentsDatabase
import socialpublish.backend.linkpreview.LinkPreviewParser
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

// Token refresh buffer: refresh 5 minutes before expiry
private const val TOKEN_REFRESH_BUFFER_SECONDS = 300L

@Serializable
data class LinkedInOAuthToken(
    val accessToken: String,
    val expiresIn: Long,
    val refreshToken: String? = null,
    val refreshTokenExpiresIn: Long? = null,
    val obtainedAt: Long = Instant.now().epochSecond,
) {
    fun isExpired(): Boolean {
        val now = Instant.now().epochSecond
        return (now - obtainedAt) >= (expiresIn - TOKEN_REFRESH_BUFFER_SECONDS)
    }
}

@Serializable
data class LinkedInTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("refresh_token_expires_in") val refreshTokenExpiresIn: Long? = null,
)

@Serializable
data class LinkedInUserProfile(
    /** Subject identifier from OIDC userinfo endpoint */
    val sub: String
)

@Serializable
data class LinkedInStatusResponse(val hasAuthorization: Boolean, val createdAt: Long? = null)

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
data class LinkedInPostRequest(
    val author: String, // "urn:li:person:{person-id}"
    val commentary: String,
    val visibility: String, // "PUBLIC", "CONNECTIONS", or "LOGGED_IN"
    val distribution: Distribution,
    val content: PostContent? = null,
    val lifecycleState: String = "PUBLISHED",
)

@Serializable
data class Distribution(
    val feedDistribution: String = "MAIN_FEED",
    val targetEntities: List<String> = emptyList(),
    val thirdPartyDistributionChannels: List<String> = emptyList(),
)

@Serializable
data class PostContent(
    val media: MediaContent? = null,
    val multiImage: MultiImageContent? = null,
    val article: ArticleContent? = null,
)

@Serializable
data class MediaContent(
    val title: String? = null,
    val id: String, // Image URN: "urn:li:image:{id}" or digitalmediaAsset URN
)

@Serializable data class MultiImageContent(val images: List<ImageContent>)

@Serializable
data class ImageContent(
    val id: String, // Image URN
    val title: String? = null,
)

@Serializable
data class ArticleContent(
    val source: String, // URL
    val title: String? = null,
    val description: String? = null,
    val thumbnail: String? = null, // Image URN
)

@Serializable data class LinkedInPostResponse(val id: String)

class LinkedInApiModule(
    private val config: LinkedInConfig,
    private val baseUrl: String,
    private val documentsDb: DocumentsDatabase,
    private val filesModule: FilesModule,
    private val httpClientEngine: HttpClientEngine,
    private val linkPreviewParser: LinkPreviewParser,
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
            baseUrl: String,
            documentsDb: DocumentsDatabase,
            filesModule: FilesModule,
        ): Resource<LinkedInApiModule> = resource {
            val engine = install({ CIO.create() }) { engine, _ -> engine.close() }
            val linkPreviewParser = LinkPreviewParser().bind()
            LinkedInApiModule(config, baseUrl, documentsDb, filesModule, engine, linkPreviewParser)
        }
    }

    /** Get OAuth callback URL */
    private fun getCallbackUrl(jwtToken: String): String {
        return "$baseUrl/api/linkedin/callback?access_token=${URLEncoder.encode(jwtToken, "UTF-8")}"
    }

    /** Check if LinkedIn auth exists */
    suspend fun hasLinkedInAuth(): Boolean {
        val token = restoreOAuthTokenFromDb()
        return token != null
    }

    /** Restore OAuth token from database */
    private suspend fun restoreOAuthTokenFromDb(): LinkedInOAuthToken? {
        val doc = documentsDb.searchByKey("linkedin-oauth-token")
        return if (doc != null) {
            try {
                Json.decodeFromString<LinkedInOAuthToken>(doc.payload)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse LinkedIn OAuth token from DB" }
                null
            }
        } else {
            null
        }
    }

    /** Build authorization URL for OAuth flow */
    suspend fun buildAuthorizeURL(jwtToken: String): ApiResult<String> {
        return try {
            val callbackUrl = getCallbackUrl(jwtToken)
            val authUrl =
                "${config.authorizationUrl}?response_type=code" +
                    "&client_id=${URLEncoder.encode(config.clientId, "UTF-8")}" +
                    "&redirect_uri=${URLEncoder.encode(callbackUrl, "UTF-8")}" +
                    "&scope=${URLEncoder.encode("openid profile w_member_social", "UTF-8")}"
            authUrl.right()
        } catch (e: Exception) {
            logger.error(e) { "Failed to build LinkedIn authorization URL" }
            CaughtException(
                    status = 500,
                    module = "linkedin",
                    errorMessage = "Failed to build authorization URL: ${e.message}",
                )
                .left()
        }
    }

    /** Exchange authorization code for access token */
    suspend fun exchangeCodeForToken(
        code: String,
        redirectUri: String,
    ): ApiResult<LinkedInOAuthToken> {
        return try {
            val response =
                httpClient.submitForm(
                    url = config.accessTokenUrl,
                    formParameters =
                        Parameters.build {
                            append("grant_type", "authorization_code")
                            append("code", code)
                            append("client_id", config.clientId)
                            append("client_secret", config.clientSecret)
                            append("redirect_uri", redirectUri)
                        },
                )

            if (response.status == HttpStatusCode.OK) {
                val tokenResponse = response.body<LinkedInTokenResponse>()
                LinkedInOAuthToken(
                        accessToken = tokenResponse.accessToken,
                        expiresIn = tokenResponse.expiresIn,
                        refreshToken = tokenResponse.refreshToken,
                        refreshTokenExpiresIn = tokenResponse.refreshTokenExpiresIn,
                    )
                    .right()
            } else {
                val errorBody = response.bodyAsText()
                logger.warn {
                    "Failed to exchange code for token: ${response.status}, body: $errorBody"
                }
                RequestError(
                        status = response.status.value,
                        module = "linkedin",
                        errorMessage = "Failed to exchange code for token",
                        body = ResponseBody(asString = errorBody),
                    )
                    .left()
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to exchange code for token" }
            CaughtException(
                    status = 500,
                    module = "linkedin",
                    errorMessage = "Failed to exchange code for token: ${e.message}",
                )
                .left()
        }
    }

    /** Refresh access token using refresh token */
    suspend fun refreshAccessToken(refreshToken: String): ApiResult<LinkedInOAuthToken> {
        return try {
            val response =
                httpClient.submitForm(
                    url = config.accessTokenUrl,
                    formParameters =
                        Parameters.build {
                            append("grant_type", "refresh_token")
                            append("refresh_token", refreshToken)
                            append("client_id", config.clientId)
                            append("client_secret", config.clientSecret)
                        },
                )

            if (response.status == HttpStatusCode.OK) {
                val tokenResponse = response.body<LinkedInTokenResponse>()
                LinkedInOAuthToken(
                        accessToken = tokenResponse.accessToken,
                        expiresIn = tokenResponse.expiresIn,
                        refreshToken = tokenResponse.refreshToken,
                        refreshTokenExpiresIn = tokenResponse.refreshTokenExpiresIn,
                    )
                    .right()
            } else {
                val errorBody = response.bodyAsText()
                logger.warn {
                    "Failed to refresh access token: ${response.status}, body: $errorBody"
                }
                RequestError(
                        status = response.status.value,
                        module = "linkedin",
                        errorMessage = "Failed to refresh access token",
                        body = ResponseBody(asString = errorBody),
                    )
                    .left()
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to refresh access token" }
            CaughtException(
                    status = 500,
                    module = "linkedin",
                    errorMessage = "Failed to refresh access token: ${e.message}",
                )
                .left()
        }
    }

    /** Save OAuth token to database */
    suspend fun saveOAuthToken(token: LinkedInOAuthToken): ApiResult<Unit> {
        return try {
            val _ =
                documentsDb.createOrUpdate(
                    kind = "linkedin-oauth-token",
                    payload = Json.encodeToString(LinkedInOAuthToken.serializer(), token),
                    searchKey = "linkedin-oauth-token",
                    tags = emptyList(),
                )
            Unit.right()
        } catch (e: Exception) {
            logger.error(e) { "Failed to save LinkedIn OAuth token" }
            CaughtException(
                    status = 500,
                    module = "linkedin",
                    errorMessage = "Failed to save OAuth token: ${e.message}",
                )
                .left()
        }
    }

    /** Get user profile to obtain person URN via OIDC userinfo endpoint */
    suspend fun getUserProfile(accessToken: String): ApiResult<LinkedInUserProfile> {
        return try {
            val response =
                httpClient.get("${config.apiBase}/userinfo") {
                    header("Authorization", "Bearer $accessToken")
                }

            if (response.status == HttpStatusCode.OK) {
                val profile = response.body<LinkedInUserProfile>()
                profile.right()
            } else {
                val errorBody = response.bodyAsText()
                logger.warn { "Failed to get user profile: ${response.status}, body: $errorBody" }
                RequestError(
                        status = response.status.value,
                        module = "linkedin",
                        errorMessage = "Failed to get user profile",
                        body = ResponseBody(asString = errorBody),
                    )
                    .left()
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get user profile" }
            CaughtException(
                    status = 500,
                    module = "linkedin",
                    errorMessage = "Failed to get user profile: ${e.message}",
                )
                .left()
        }
    }

    /** Get valid access token, refreshing if needed. Returns (accessToken, personUrn) */
    suspend fun getValidToken(): ApiResult<Pair<String, String>> {
        val token =
            restoreOAuthTokenFromDb()
                ?: return ValidationError(
                        status = 401,
                        errorMessage = "Unauthorized: Missing LinkedIn OAuth token!",
                        module = "linkedin",
                    )
                    .left()

        val validToken =
            if (token.isExpired()) {
                if (token.refreshToken == null) {
                    return ValidationError(
                            status = 401,
                            errorMessage =
                                "LinkedIn token expired and no refresh token available. Please re-authorize.",
                            module = "linkedin",
                        )
                        .left()
                }

                logger.info { "LinkedIn token expired, refreshing..." }
                when (val result = refreshAccessToken(token.refreshToken)) {
                    is Either.Right -> {
                        val newToken = result.value
                        when (val saveResult = saveOAuthToken(newToken)) {
                            is Either.Right -> newToken
                            is Either.Left -> return saveResult.value.left()
                        }
                    }
                    is Either.Left -> return result.value.left()
                }
            } else {
                token
            }

        // Get person URN from user profile
        when (val profileResult = getUserProfile(validToken.accessToken)) {
            is Either.Right -> {
                // The OIDC /userinfo endpoint returns the subject ID in "sub" field
                // Normalize to always use the full URN format
                val rawId = profileResult.value.sub
                val personUrn =
                    if (rawId.startsWith("urn:li:person:")) {
                        rawId
                    } else {
                        "urn:li:person:$rawId"
                    }
                return (validToken.accessToken to personUrn).right()
            }
            is Either.Left -> return profileResult.value.left()
        }
    }

    /** Upload media from bytes to LinkedIn */
    private suspend fun uploadMediaFromBytes(
        accessToken: String,
        personUrn: String,
        bytes: ByteArray,
        mimetype: String,
    ): ApiResult<String> {
        return try {
            // Step 1: Register upload
            val registerRequest =
                LinkedInRegisterUploadRequest(
                    registerUploadRequest =
                        RegisterUploadRequestData(
                            owner = personUrn,
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
                    header("Authorization", "Bearer $accessToken")
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
                    header("Authorization", "Bearer $accessToken")
                    contentType(ContentType.parse(mimetype))
                    setBody(bytes)
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
            logger.error(e) { "Failed to upload media from bytes to LinkedIn" }
            CaughtException(
                    status = 500,
                    module = "linkedin",
                    errorMessage = "Failed to upload media from bytes: ${e.message}",
                )
                .left()
        }
    }

    /** Upload media to LinkedIn */
    private suspend fun uploadMedia(
        accessToken: String,
        personUrn: String,
        uuid: String,
    ): ApiResult<String> {
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
                            owner = personUrn,
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
                    header("Authorization", "Bearer $accessToken")
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
                    header("Authorization", "Bearer $accessToken")
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

    /** Fetch link preview metadata using LinkPreviewParser */
    private suspend fun fetchLinkPreview(url: String): Pair<String?, String?> {
        return try {
            val preview = linkPreviewParser.fetchPreview(url)
            if (preview != null) {
                Pair(preview.title, preview.image)
            } else {
                Pair(null, null)
            }
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

            // Get valid OAuth token and person URN
            val (accessToken, personUrn) =
                when (val result = getValidToken()) {
                    is Either.Right -> result.value
                    is Either.Left -> return result.value.left()
                }

            // Prepare text content
            val content =
                if (request.cleanupHtml == true) {
                    cleanupHtml(request.content)
                } else {
                    request.content
                }

            logger.info { "Posting to LinkedIn:\n${content.trim().prependIndent("  |")}" }

            // Upload images if present
            val uploadedImageUrns = mutableListOf<String>()
            if (!request.images.isNullOrEmpty()) {
                for (imageUuid in request.images) {
                    when (val result = uploadMedia(accessToken, personUrn, imageUuid)) {
                        is Either.Right -> uploadedImageUrns.add(result.value)
                        is Either.Left -> return result.value.left()
                    }
                }
            }

            // Build post content based on what we have
            val postContent: PostContent? =
                when {
                    // If we have multiple images, use multi-image content
                    uploadedImageUrns.size > 1 -> {
                        PostContent(
                            multiImage =
                                MultiImageContent(
                                    images = uploadedImageUrns.map { ImageContent(id = it) }
                                )
                        )
                    }
                    // If we have a single image, use media content
                    uploadedImageUrns.size == 1 -> {
                        PostContent(media = MediaContent(id = uploadedImageUrns.first()))
                    }
                    // If we have a link (and no images), create article content with link
                    // preview
                    request.link != null -> {
                        val (title, imageUrl) = fetchLinkPreview(request.link)

                        // Download and upload thumbnail image to LinkedIn if available
                        val thumbnailUrn: String? =
                            if (imageUrl != null) {
                                try {
                                    val imageResponse = httpClient.get(imageUrl)
                                    if (imageResponse.status == HttpStatusCode.OK) {
                                        val imageBytes = imageResponse.body<ByteArray>()
                                        val contentType =
                                            imageResponse.headers["Content-Type"] ?: "image/jpeg"
                                        when (
                                            val uploadResult =
                                                uploadMediaFromBytes(
                                                    accessToken,
                                                    personUrn,
                                                    imageBytes,
                                                    contentType,
                                                )
                                        ) {
                                            is Either.Right -> uploadResult.value
                                            is Either.Left -> {
                                                logger.warn {
                                                    "Failed to upload article thumbnail, continuing without it"
                                                }
                                                null
                                            }
                                        }
                                    } else {
                                        logger.warn {
                                            "Failed to download article thumbnail: ${imageResponse.status}"
                                        }
                                        null
                                    }
                                } catch (e: Exception) {
                                    logger.warn(e) { "Failed to download/upload article thumbnail" }
                                    null
                                }
                            } else {
                                null
                            }

                        PostContent(
                            article =
                                ArticleContent(
                                    source = request.link,
                                    title = title,
                                    description = content.take(256), // LinkedIn limit
                                    thumbnail = thumbnailUrn,
                                )
                        )
                    }
                    // Text only
                    else -> null
                }

            // Create post using new Posts API
            val postRequest =
                LinkedInPostRequest(
                    author = personUrn,
                    commentary = content,
                    visibility = "PUBLIC",
                    distribution = Distribution(),
                    content = postContent,
                    lifecycleState = "PUBLISHED",
                )

            val response =
                httpClient.post("${config.apiBase}/posts") {
                    header("Authorization", "Bearer $accessToken")
                    header("LinkedIn-Version", "202401")
                    header("X-Restli-Protocol-Version", "2.0.0")
                    contentType(ContentType.Application.Json)
                    setBody(postRequest)
                }

            if (response.status == HttpStatusCode.Created) {
                val data = response.body<LinkedInPostResponse>()
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

    /** Handle authorize redirect HTTP route */
    suspend fun authorizeRoute(call: ApplicationCall, jwtToken: String) {
        when (val result = buildAuthorizeURL(jwtToken)) {
            is Either.Right -> call.respondRedirect(result.value)
            is Either.Left -> {
                val error = result.value
                call.respond(
                    HttpStatusCode.fromValue(error.status),
                    ErrorResponse(error = error.errorMessage),
                )
            }
        }
    }

    /** Handle OAuth callback HTTP route */
    suspend fun callbackRoute(call: ApplicationCall) {
        val code = call.request.queryParameters["code"]
        val accessToken = call.request.queryParameters["access_token"]
        val error = call.request.queryParameters["error"]
        val errorDescription = call.request.queryParameters["error_description"]

        // Check if LinkedIn returned an error
        if (error != null) {
            val errorMsg =
                errorDescription?.let { URLEncoder.encode(it, "UTF-8") }
                    ?: URLEncoder.encode("LinkedIn authorization failed: $error", "UTF-8")
            logger.warn { "LinkedIn OAuth error: $error, description: $errorDescription" }
            call.respondRedirect("/account?error=$errorMsg")
            return
        }

        if (code == null) {
            val errorMsg =
                URLEncoder.encode(
                    "LinkedIn authorization failed: missing authorization code",
                    "UTF-8",
                )
            logger.warn { "LinkedIn callback missing code parameter" }
            call.respondRedirect("/account?error=$errorMsg")
            return
        }

        logger.info { "LinkedIn auth callback: code=$code" }

        // Reconstruct the original redirect_uri (must match the one used in authorization)
        val redirectUri =
            if (accessToken != null) {
                "$baseUrl/api/linkedin/callback?access_token=${URLEncoder.encode(accessToken, "UTF-8")}"
            } else {
                "$baseUrl/api/linkedin/callback"
            }

        when (val tokenResult = exchangeCodeForToken(code, redirectUri)) {
            is Either.Right -> {
                val token = tokenResult.value
                when (val saveResult = saveOAuthToken(token)) {
                    is Either.Right -> {
                        call.response.header(
                            "Cache-Control",
                            "no-store, no-cache, must-revalidate, private",
                        )
                        call.response.header("Pragma", "no-cache")
                        call.response.header("Expires", "0")
                        call.respondRedirect("/account")
                    }
                    is Either.Left -> {
                        val saveError = saveResult.value
                        val errorMsg = URLEncoder.encode(saveError.errorMessage, "UTF-8")
                        logger.error { "Failed to save LinkedIn token: ${saveError.errorMessage}" }
                        call.respondRedirect("/account?error=$errorMsg")
                    }
                }
            }
            is Either.Left -> {
                val tokenError = tokenResult.value
                val errorMsg = URLEncoder.encode(tokenError.errorMessage, "UTF-8")
                logger.error {
                    "Failed to exchange LinkedIn code for token: ${tokenError.errorMessage}"
                }
                call.respondRedirect("/account?error=$errorMsg")
            }
        }
    }

    /** Handle status check HTTP route */
    suspend fun statusRoute(call: ApplicationCall) {
        val row = documentsDb.searchByKey("linkedin-oauth-token")
        call.respond(
            LinkedInStatusResponse(
                hasAuthorization = row != null,
                createdAt = row?.createdAt?.toEpochMilli(),
            )
        )
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
