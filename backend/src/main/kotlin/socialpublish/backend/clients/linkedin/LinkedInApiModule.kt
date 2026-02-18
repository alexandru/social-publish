@file:Suppress("BlockingMethodInNonBlockingContext")

package socialpublish.backend.clients.linkedin

/**
 * LinkedIn API integration module using OpenID Connect (OIDC) and UGC (User Generated Content) API.
 *
 * This module provides complete integration with LinkedIn's social platform, including:
 * - OAuth2 authentication with OpenID Connect (3-legged OAuth flow)
 * - User profile retrieval via OIDC UserInfo endpoint
 * - Post creation with text, images, and link previews via UGC API
 * - Automatic token refresh management
 *
 * ## Prerequisites
 *
 * Before using this module, you must:
 * 1. Create a LinkedIn App at https://www.linkedin.com/developers/apps
 * 2. Request access to these products in the LinkedIn Developer Portal:
 *     - **Sign In with LinkedIn using OpenID Connect** (provides `openid` and `profile` scopes)
 *     - **Share on LinkedIn** (provides `w_member_social` scope)
 * 3. Configure redirect URL: `{baseUrl}/api/linkedin/callback`
 * 4. Set environment variables: `LINKEDIN_CLIENT_ID` and `LINKEDIN_CLIENT_SECRET`
 *
 * ## API Documentation
 * - [LinkedIn Developer Portal](https://www.linkedin.com/developers/)
 * - [OAuth 2.0
 *   Documentation](https://learn.microsoft.com/en-us/linkedin/shared/authentication/authentication)
 * - [Sign In with LinkedIn using OpenID
 *   Connect](https://learn.microsoft.com/en-us/linkedin/consumer/integrations/self-serve/sign-in-with-linkedin-v2)
 * - [Share on
 *   LinkedIn](https://learn.microsoft.com/en-us/linkedin/consumer/integrations/self-serve/share-on-linkedin)
 * - [UGC Posts
 *   API](https://learn.microsoft.com/en-us/linkedin/marketing/integrations/community-management/shares/ugc-post-api)
 *
 * ## Token Management
 * - Access tokens expire after 60 days
 * - Refresh tokens expire after 1 year
 * - Tokens are automatically refreshed when expired (with 5-minute buffer)
 * - All tokens are stored securely in the database
 *
 * @see LinkedInApiModule Main API client class
 * @see LinkedInConfig Configuration for OAuth credentials and API endpoints
 */
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
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import socialpublish.backend.clients.linkpreview.LinkPreviewParser
import socialpublish.backend.common.*
import socialpublish.backend.db.DocumentsDatabase
import socialpublish.backend.modules.FilesModule

private val logger = KotlinLogging.logger {}

/**
 * LinkedIn API integration for OAuth2 authentication and posting to LinkedIn.
 *
 * This module provides integration with LinkedIn's APIs using OpenID Connect (OIDC) for
 * authentication and the UGC (User Generated Content) API for creating posts with text and media.
 *
 * ## Required LinkedIn Products
 *
 * In the LinkedIn Developer Portal, request access to:
 * - **Sign In with LinkedIn using OpenID Connect** - Provides `openid` and `profile` scopes
 * - **Share on LinkedIn** - Provides `w_member_social` scope
 *
 * ## API Documentation
 * - [LinkedIn OAuth
 *   2.0](https://learn.microsoft.com/en-us/linkedin/shared/authentication/authentication)
 * - [Sign In with LinkedIn using OpenID
 *   Connect](https://learn.microsoft.com/en-us/linkedin/consumer/integrations/self-serve/sign-in-with-linkedin-v2)
 * - [Share on
 *   LinkedIn](https://learn.microsoft.com/en-us/linkedin/consumer/integrations/self-serve/share-on-linkedin)
 * - [UGC Posts
 *   API](https://learn.microsoft.com/en-us/linkedin/marketing/integrations/community-management/shares/ugc-post-api)
 *
 * ## OAuth Flow (3-legged)
 * 1. Call [buildAuthorizeURL] to get the authorization URL with state parameter
 * 2. Redirect user to LinkedIn for consent
 * 3. LinkedIn redirects back to callback URL with auth code and state
 * 4. Call [callbackRoute] to verify state and exchange code for access token
 * 5. Token is stored in database and automatically refreshed when needed
 *
 * ## Creating Posts via UGC API
 *
 * Use [createPost] to publish content. The module supports:
 * - Text-only posts (shareMediaCategory: NONE)
 * - Posts with article/link previews (shareMediaCategory: ARTICLE)
 * - Posts with single or multiple images (shareMediaCategory: IMAGE)
 *
 * @property config LinkedIn OAuth2 client credentials and API endpoints
 * @property baseUrl Base URL of this application (for OAuth callbacks)
 * @property documentsDb Database for storing OAuth tokens
 * @property filesModule Module for handling file uploads
 * @property httpClientEngine HTTP client engine for API requests
 * @property linkPreviewParser Parser for extracting link preview metadata
 */
class LinkedInApiModule(
    val baseUrl: String,
    private val documentsDb: DocumentsDatabase,
    private val filesModule: FilesModule,
    private val httpClientEngine: HttpClientEngine,
    private val linkPreviewParser: LinkPreviewParser,
) {
    private val httpClient: HttpClient by lazy {
        HttpClient(httpClientEngine) { install(ContentNegotiation) { json(jsonConfig) } }
    }

    companion object {
        // Shared JSON instance for serialization/deserialization
        private val jsonConfig = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
            // Don't encode null fields in JSON - CRITICAL for LinkedIn API compatibility.
            // LinkedIn's API rejects requests containing null 'content' field with:
            // "Unpermitted fields present in REQUEST_BODY: Data Processing Exception
            // while processing fields [/content]"
            // Text-only posts must omit the 'content' field entirely, not send it as null.
            explicitNulls = false
        }

        // Shared JSON instance for pretty printing logs
        private val prettyJson = Json { prettyPrint = true }

        // Number of characters to show from authorization token in logs
        private const val AUTH_TOKEN_PREVIEW_LENGTH = 20

        fun resource(
            baseUrl: String,
            documentsDb: DocumentsDatabase,
            filesModule: FilesModule,
        ): Resource<LinkedInApiModule> = resource {
            val engine = install({ CIO.create() }) { engine, _ -> engine.close() }
            val linkPreviewParser = LinkPreviewParser().bind()
            LinkedInApiModule(baseUrl, documentsDb, filesModule, engine, linkPreviewParser)
        }
    }

    /** Get OAuth callback URL */
    private fun getCallbackUrl(jwtToken: String): String {
        return "$baseUrl/api/linkedin/callback?access_token=${URLEncoder.encode(jwtToken, "UTF-8")}"
    }

    /**
     * Generate a cryptographically secure random state string for CSRF protection.
     *
     * The state parameter prevents CSRF attacks during the OAuth flow. LinkedIn will return this
     * value in the callback, and we verify it matches the original.
     */
    private fun generateOAuthState(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** Save OAuth state to database for verification during callback */
    private suspend fun saveOAuthState(state: String, jwtToken: String, userUuid: UUID) {
        val _ =
            documentsDb.createOrUpdate(
                kind = "linkedin-oauth-state",
                payload = """{"state":"$state","jwtToken":"$jwtToken"}""",
                userUuid = userUuid,
                searchKey = state,
                tags = emptyList(),
            )
    }

    /** Verify and consume OAuth state during callback */
    suspend fun verifyOAuthState(state: String, userUuid: UUID): String? {
        val doc = documentsDb.searchByKey(state, userUuid).getOrElse { throw it }
        return if (doc != null && doc.kind == "linkedin-oauth-state") {
            // State found and valid (we don't delete it, but could track usage)
            // In production, we might want to track used states to prevent replay attacks
            try {
                val json = Json.parseToJsonElement(doc.payload)
                json.jsonObject["jwtToken"]?.jsonPrimitive?.content
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse OAuth state from DB" }
                null
            }
        } else {
            null
        }
    }

    /** Pretty print JSON string for logging, or return original if not valid JSON */
    private fun prettyPrintJson(json: String): String {
        return try {
            val jsonElement = Json.parseToJsonElement(json)
            prettyJson.encodeToString(
                kotlinx.serialization.json.JsonElement.serializer(),
                jsonElement,
            )
        } catch (e: Exception) {
            logger.warn(e) { "Failed to pretty print JSON for logging" }
            json
        }
    }

    /** Format HTTP request for logging with nice formatting */
    private fun formatHttpRequest(
        @Suppress("SameParameterValue") method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("HTTP Request:")
        sb.appendLine("  Method: $method")
        sb.appendLine("  URL: $url")
        if (headers.isNotEmpty()) {
            sb.appendLine("  Headers:")
            headers.forEach { (key, value) ->
                // Mask sensitive headers
                val maskedValue =
                    if (key.equals("Authorization", ignoreCase = true)) {
                        value.take(AUTH_TOKEN_PREVIEW_LENGTH) + "..."
                    } else {
                        value
                    }
                sb.appendLine("    $key: $maskedValue")
            }
        }
        if (body != null) {
            sb.appendLine("  Body:")
            sb.append(prettyPrintJson(body).prependIndent("    "))
        }
        return sb.toString()
    }

    /** Format HTTP response for logging with nice formatting */
    private fun formatHttpResponse(
        statusCode: Int,
        statusText: String,
        headers: Map<String, List<String>>,
        body: String?,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("HTTP Response:")
        sb.appendLine("  Status: $statusCode $statusText")
        if (headers.isNotEmpty()) {
            sb.appendLine("  Headers:")
            headers.forEach { (key, values) ->
                values.forEach { value -> sb.appendLine("    $key: $value") }
            }
        }
        if (!body.isNullOrEmpty()) {
            sb.appendLine("  Body:")
            sb.append(prettyPrintJson(body).prependIndent("    "))
        }
        return sb.toString()
    }

    /** Check if LinkedIn auth exists for the given user */
    suspend fun hasLinkedInAuth(userUuid: UUID): Boolean {
        val token = restoreOAuthTokenFromDb(userUuid)
        return token != null
    }

    /** Restore OAuth token from database (scoped to the user) */
    private suspend fun restoreOAuthTokenFromDb(userUuid: UUID): LinkedInOAuthToken? {
        val doc =
            documentsDb.searchByKey("linkedin-oauth-token:$userUuid", userUuid).getOrElse {
                throw it
            }
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

    /**
     * Build the LinkedIn OAuth2 authorization URL.
     *
     * Constructs the URL to redirect users to LinkedIn for OAuth consent. Uses OpenID Connect
     * (OIDC) scopes: `openid`, `profile`, and `w_member_social`.
     *
     * Includes a cryptographically secure `state` parameter for CSRF protection as required by the
     * OAuth 2.0 spec and LinkedIn's API.
     *
     * **API Reference:**
     * - [Authorization Code
     *   Flow](https://learn.microsoft.com/en-us/linkedin/shared/authentication/authorization-code-flow)
     *
     * @param jwtToken JWT token to include in callback URL for state verification
     * @return Authorization URL to redirect the user to, or an error
     */
    suspend fun buildAuthorizeURL(
        config: LinkedInConfig,
        jwtToken: String,
        userUuid: UUID,
    ): ApiResult<String> {
        return try {
            val callbackUrl = getCallbackUrl(jwtToken)
            val state = generateOAuthState()

            // Save state for verification during callback
            saveOAuthState(state, jwtToken, userUuid)

            val authUrl =
                "${config.authorizationUrl}?response_type=code" +
                    "&client_id=${URLEncoder.encode(config.clientId, "UTF-8")}" +
                    "&redirect_uri=${URLEncoder.encode(callbackUrl, "UTF-8")}" +
                    "&state=${URLEncoder.encode(state, "UTF-8")}" +
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
        config: LinkedInConfig,
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
    suspend fun refreshAccessToken(
        config: LinkedInConfig,
        refreshToken: String,
    ): ApiResult<LinkedInOAuthToken> {
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

    /** Save OAuth token to database (scoped to the user) */
    suspend fun saveOAuthToken(token: LinkedInOAuthToken, userUuid: UUID): ApiResult<Unit> {
        return try {
            val _ =
                documentsDb.createOrUpdate(
                    kind = "linkedin-oauth-token",
                    payload = Json.encodeToString(LinkedInOAuthToken.serializer(), token),
                    userUuid = userUuid,
                    searchKey = "linkedin-oauth-token:$userUuid",
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

    /**
     * Get user profile information from LinkedIn's OIDC UserInfo endpoint.
     *
     * Uses the standard OpenID Connect `/userinfo` endpoint to retrieve the user's subject
     * identifier (sub), which contains the LinkedIn member ID.
     *
     * This endpoint works with the `openid` and `profile` OAuth scopes and returns standard OIDC
     * claims. The `sub` field contains either a plain member ID or the full URN format
     * (`urn:li:person:...`).
     *
     * **API Reference:**
     * - [OpenID Connect
     *   UserInfo](https://learn.microsoft.com/en-us/linkedin/consumer/integrations/self-serve/sign-in-with-linkedin-v2#retrieving-member-profiles)
     * - [OIDC Standard](https://openid.net/specs/openid-connect-core-1_0.html#UserInfo)
     *
     * @param accessToken OAuth2 access token with `openid` and `profile` scopes
     * @return User profile containing the subject identifier, or an error
     */
    suspend fun getUserProfile(
        config: LinkedInConfig,
        accessToken: String,
    ): ApiResult<LinkedInUserProfile> {
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
    suspend fun getValidToken(
        config: LinkedInConfig,
        userUuid: UUID,
    ): ApiResult<Pair<String, String>> {
        val token =
            restoreOAuthTokenFromDb(userUuid)
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
                when (val result = refreshAccessToken(config, token.refreshToken)) {
                    is Either.Right -> {
                        val newToken = result.value
                        when (val saveResult = saveOAuthToken(newToken, userUuid)) {
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
        when (val profileResult = getUserProfile(config, validToken.accessToken)) {
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

    /** Upload media to LinkedIn */
    private suspend fun uploadMedia(
        config: LinkedInConfig,
        accessToken: String,
        personUrn: String,
        uuid: String,
    ): ApiResult<UploadedAsset> = resourceScope {
        try {
            val file =
                filesModule.readImageFile(uuid)
                    ?: return@resourceScope ValidationError(
                            status = 404,
                            errorMessage = "Failed to read image file — uuid: $uuid",
                            module = "linkedin",
                        )
                        .left()

            uploadMediaFromBytes(
                config = config,
                accessToken = accessToken,
                personUrn = personUrn,
                fileSource = file.source,
                mimetype = file.mimetype,
                altText = file.altText,
            )
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

    private suspend fun uploadMediaFromBytes(
        config: LinkedInConfig,
        accessToken: String,
        personUrn: String,
        fileSource: UploadSource,
        mimetype: String,
        altText: String?,
    ): ApiResult<UploadedAsset> = resourceScope {
        try {
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
                    setBody(ByteReadChannel(fileSource.asKotlinSource().bind()))
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
            // Return asset URN along with optional alt text stored in file metadata
            UploadedAsset(asset = asset, description = altText).right()
        } catch (e: Exception) {
            logger.error(e) { "Failed to upload media to LinkedIn from bytes" }
            CaughtException(
                    status = 500,
                    module = "linkedin",
                    errorMessage = "Failed to upload media from bytes: ${e.message}",
                )
                .left()
        }
    }

    /** Fetch link preview metadata using LinkPreviewParser */
    private suspend fun fetchLinkPreview(url: String) =
        try {
            linkPreviewParser.fetchPreview(url)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to fetch link preview for $url" }
            null
        }

    /**
     * Create a post on LinkedIn with optional text, images, and link previews.
     *
     * This function uses LinkedIn's UGC (User Generated Content) API to publish content. It
     * supports:
     * - Text-only posts (shareMediaCategory: NONE)
     * - Posts with article/link previews (shareMediaCategory: ARTICLE)
     * - Posts with single or multiple images (shareMediaCategory: IMAGE)
     *
     * The function automatically:
     * - Retrieves and refreshes OAuth tokens as needed
     * - Uploads images to LinkedIn's media service
     * - Fetches link preview metadata for URLs
     * - Normalizes person URN format
     *
     * **API Reference:**
     * - [Share on
     *   LinkedIn](https://learn.microsoft.com/en-us/linkedin/consumer/integrations/self-serve/share-on-linkedin)
     * - [UGC Posts
     *   API](https://learn.microsoft.com/en-us/linkedin/marketing/integrations/community-management/shares/ugc-post-api)
     *
     * **Required Header:** `X-Restli-Protocol-Version: 2.0.0`
     *
     * @param request Post content including text, images, and links
     * @return Post response with created post ID, or an error
     */
    suspend fun createPost(
        config: LinkedInConfig,
        request: NewPostRequest,
        userUuid: UUID,
    ): ApiResult<NewPostResponse> {
        return try {
            // Validate request
            request.validate()?.let { error ->
                return error.left()
            }

            // Get valid OAuth token and person URN
            val (accessToken, personUrn) =
                when (val result = getValidToken(config, userUuid)) {
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

            logger.info {
                "Posting to LinkedIn via UGC API:\n${content.trim().prependIndent("  |")}"
            }

            // Upload images if present
            val uploadedAssets = mutableListOf<UploadedAsset>()
            if (!request.images.isNullOrEmpty()) {
                for (imageUuid in request.images) {
                    when (val result = uploadMedia(config, accessToken, personUrn, imageUuid)) {
                        is Either.Right -> uploadedAssets.add(result.value)
                        is Either.Left -> return result.value.left()
                    }
                }
            }

            // Build UGC post request based on content type
            val ugcPostRequest =
                when {
                    // If we have images, create IMAGE share
                    uploadedAssets.isNotEmpty() -> {
                        UgcPostRequest(
                            author = personUrn,
                            lifecycleState = UgcLifecycleState.PUBLISHED,
                            specificContent =
                                UgcSpecificContent(
                                    shareContent =
                                        UgcShareContent(
                                            shareCommentary = UgcText(content),
                                            shareMediaCategory = UgcMediaCategory.IMAGE,
                                            media =
                                                uploadedAssets.map { uploaded ->
                                                    UgcMedia(
                                                        status = "READY",
                                                        media = uploaded.asset,
                                                        description =
                                                            uploaded.description?.let {
                                                                UgcText(it)
                                                            },
                                                    )
                                                },
                                        )
                                ),
                            visibility = UgcVisibility(UgcVisibilityType.PUBLIC),
                        )
                    }
                    // If we have a link (and no images), create ARTICLE share
                    request.link != null -> {
                        val linkPreview = fetchLinkPreview(request.link)
                        UgcPostRequest(
                            author = personUrn,
                            lifecycleState = UgcLifecycleState.PUBLISHED,
                            specificContent =
                                UgcSpecificContent(
                                    shareContent =
                                        UgcShareContent(
                                            shareCommentary = UgcText(content),
                                            shareMediaCategory = UgcMediaCategory.ARTICLE,
                                            media =
                                                listOf(
                                                    UgcMedia(
                                                        status = "READY",
                                                        originalUrl = request.link,
                                                        title =
                                                            linkPreview?.title?.let { UgcText(it) },
                                                        description = UgcText(content.take(256)),
                                                        thumbnails =
                                                            linkPreview?.image?.let {
                                                                listOf(UgcThumbnail(url = it))
                                                            },
                                                    )
                                                ),
                                        )
                                ),
                            visibility = UgcVisibility(UgcVisibilityType.PUBLIC),
                        )
                    }
                    // Text-only post
                    else -> {
                        UgcPostRequest(
                            author = personUrn,
                            lifecycleState = UgcLifecycleState.PUBLISHED,
                            specificContent =
                                UgcSpecificContent(
                                    shareContent =
                                        UgcShareContent(
                                            shareCommentary = UgcText(content),
                                            shareMediaCategory = UgcMediaCategory.NONE,
                                        )
                                ),
                            visibility = UgcVisibility(UgcVisibilityType.PUBLIC),
                        )
                    }
                }

            // Serialize request body for logging
            val requestBody = jsonConfig.encodeToString(UgcPostRequest.serializer(), ugcPostRequest)
            val requestUrl = "${config.apiBase}/ugcPosts"
            val requestHeaders =
                mapOf(
                    "Authorization" to "Bearer $accessToken",
                    "X-Restli-Protocol-Version" to "2.0.0",
                    "Content-Type" to "application/json",
                )

            // Log the HTTP request
            logger.info { formatHttpRequest("POST", requestUrl, requestHeaders, requestBody) }

            val response =
                httpClient.post(requestUrl) {
                    header("Authorization", "Bearer $accessToken")
                    header("X-Restli-Protocol-Version", "2.0.0")
                    contentType(ContentType.Application.Json)
                    setBody(ugcPostRequest)
                }

            // Get response body for logging
            val responseBody = response.bodyAsText()

            // Log the HTTP response
            val responseHeaders =
                response.headers.entries().groupBy({ it.key }, { it.value }).mapValues {
                    it.value.flatten()
                }
            logger.info {
                formatHttpResponse(
                    response.status.value,
                    response.status.description,
                    responseHeaders,
                    responseBody,
                )
            }

            if (response.status == HttpStatusCode.Created) {
                // The post ID is returned in the X-RestLi-Id response header
                val postId =
                    response.headers["X-RestLi-Id"]
                        ?: run {
                            // Try to get it from the response body as fallback
                            try {
                                val data =
                                    jsonConfig.decodeFromString<UgcPostResponse>(responseBody)
                                data.id ?: "unknown"
                            } catch (e: Exception) {
                                logger.error(e) { "Could not parse postId: $responseBody" }
                                "unknown"
                            }
                        }
                NewLinkedInPostResponse(postId = postId).right()
            } else {
                logger.warn { "Failed to post to LinkedIn via UGC API: ${response.status}" }
                RequestError(
                        status = response.status.value,
                        module = "linkedin",
                        errorMessage = "Failed to create post",
                        body = ResponseBody(asString = responseBody),
                    )
                    .left()
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to post to LinkedIn via UGC API" }
            CaughtException(
                    status = 500,
                    module = "linkedin",
                    errorMessage = "Failed to post to LinkedIn: ${e.message}",
                )
                .left()
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
