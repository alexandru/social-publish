@file:Suppress("PropertyName")

package socialpublish.backend.clients.twitter

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import arrow.fx.coroutines.Resource
import arrow.fx.coroutines.resource
import com.github.scribejava.core.builder.ServiceBuilder
import com.github.scribejava.core.builder.api.DefaultApi10a
import com.github.scribejava.core.model.OAuth1AccessToken
import com.github.scribejava.core.model.OAuth1RequestToken
import com.github.scribejava.core.model.OAuthRequest
import com.github.scribejava.core.model.Verb
import com.github.scribejava.core.oauth.OAuth10aService
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.accept
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import socialpublish.backend.db.DocumentsDatabase
import socialpublish.backend.models.*
import socialpublish.backend.modules.FilesModule
import socialpublish.backend.utils.LOOM

private val logger = KotlinLogging.logger {}

/** Twitter API module with OAuth 1.0a implementation */
class TwitterApiModule(
    private val config: TwitterConfig,
    private val baseUrl: String,
    private val documentsDb: DocumentsDatabase,
    private val filesModule: FilesModule,
    private val httpClient: HttpClient,
) {
    companion object {
        fun defaultHttpClient(): Resource<HttpClient> = resource {
            install(
                {
                    HttpClient(CIO) {
                        install(ContentNegotiation) {
                            json(
                                Json {
                                    ignoreUnknownKeys = true
                                    isLenient = true
                                }
                            )
                        }
                    }
                },
                { client, _ -> client.close() },
            )
        }

        fun resource(
            config: TwitterConfig,
            baseUrl: String,
            documentsDb: DocumentsDatabase,
            filesModule: FilesModule,
        ): Resource<TwitterApiModule> = resource {
            TwitterApiModule(config, baseUrl, documentsDb, filesModule, defaultHttpClient().bind())
        }
    }

    private class TwitterApi(private val config: TwitterConfig) : DefaultApi10a() {
        override fun getRequestTokenEndpoint() = config.oauthRequestTokenUrl

        override fun getAccessTokenEndpoint() = config.oauthAccessTokenUrl

        override fun getAuthorizationBaseUrl() = config.oauthAuthorizeUrl
    }

    private fun createOAuthService(callback: String? = null): OAuth10aService {
        val builder =
            ServiceBuilder(config.oauth1ConsumerKey).apiSecret(config.oauth1ConsumerSecret)
        if (callback != null) {
            builder.callback(callback)
        }
        return builder.build(TwitterApi(config))
    }

    /** Get OAuth callback URL */
    private fun getCallbackUrl(jwtToken: String): String {
        return "$baseUrl/api/twitter/callback?access_token=${URLEncoder.encode(jwtToken, "UTF-8")}"
    }

    /** Check if Twitter auth exists */
    suspend fun hasTwitterAuth(): Boolean {
        val token = restoreOauthTokenFromDb()
        return token != null
    }

    /** Restore OAuth token from database */
    private suspend fun restoreOauthTokenFromDb(): TwitterOAuthToken? {
        val doc = documentsDb.searchByKey("twitter-oauth-token").getOrElse { throw it }
        return if (doc != null) {
            try {
                Json.decodeFromString<TwitterOAuthToken>(doc.payload)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse Twitter OAuth token from DB" }
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
            val service = createOAuthService(callbackUrl)
            val token = withContext(Dispatchers.LOOM) { service.requestToken }
            val authUrl = service.getAuthorizationUrl(token)
            authUrl.right()
        } catch (e: Exception) {
            logger.error(e) { "Failed to get Twitter request token" }
            CaughtException(
                    status = 500,
                    module = "twitter",
                    errorMessage = "Failed to get request token: ${e.message}",
                )
                .left()
        }
    }

    /** Save OAuth token after callback */
    suspend fun saveOauthToken(token: String, verifier: String): ApiResult<Unit> {
        return try {
            // Twitter's access token endpoint doesn't require the request token secret
            // in the OAuth signature, only the oauth_token and oauth_verifier parameters
            val reqToken = OAuth1RequestToken(token, "")
            val service = createOAuthService()
            val accessToken =
                withContext(Dispatchers.LOOM) { service.getAccessToken(reqToken, verifier) }

            val authorizedToken =
                TwitterOAuthToken(key = accessToken.token, secret = accessToken.tokenSecret)

            val _ =
                documentsDb.createOrUpdate(
                    kind = "twitter-oauth-token",
                    payload = Json.encodeToString(TwitterOAuthToken.serializer(), authorizedToken),
                    searchKey = "twitter-oauth-token",
                    tags = emptyList(),
                )

            Unit.right()
        } catch (e: Exception) {
            logger.error(e) { "Failed to save Twitter OAuth token" }
            CaughtException(
                    status = 500,
                    module = "twitter",
                    errorMessage = "Failed to save OAuth token: ${e.message}",
                )
                .left()
        }
    }

    /** Sign a request and return the Authorization header */
    private suspend fun signRequest(
        url: String,
        token: TwitterOAuthToken,
        verb: Verb = Verb.POST,
    ): String =
        withContext(Dispatchers.LOOM) {
            val service = createOAuthService()
            val accessToken = OAuth1AccessToken(token.key, token.secret)
            val request = OAuthRequest(verb, url)
            service.signRequest(accessToken, request)
            request.headers["Authorization"]
                ?: throw IllegalStateException("Authorization header not found")
        }

    /** Upload media to Twitter */
    private suspend fun uploadMedia(token: TwitterOAuthToken, uuid: String): ApiResult<String> {
        return try {
            val file =
                filesModule.readImageFile(uuid)
                    ?: return ValidationError(
                            status = 404,
                            errorMessage = "Failed to read image file — uuid: $uuid",
                            module = "twitter",
                        )
                        .left()

            val url = "${config.uploadBase}/1.1/media/upload.json"

            // Generate auth header by signing URL
            val authHeader = signRequest(url, token)

            val response =
                httpClient.submitFormWithBinaryData(
                    url = url,
                    formData =
                        formData {
                            append(
                                "media",
                                file.bytes,
                                Headers.build {
                                    append(HttpHeaders.ContentType, file.mimetype)
                                    append(
                                        HttpHeaders.ContentDisposition,
                                        "filename=\"${file.originalname}\"",
                                    )
                                },
                            )
                            append("media_category", "tweet_image")
                        },
                ) {
                    header("Authorization", authHeader)
                }

            if (response.status.value == 200) {
                val data = response.body<TwitterMediaResponse>()
                val mediaId = data.media_id_string

                // Add alt text if present
                if (!file.altText.isNullOrEmpty()) {
                    val altTextUrl = "${config.apiBase}/1.1/media/metadata/create.json"
                    val altAuthHeader = signRequest(altTextUrl, token)

                    httpClient.post(altTextUrl) {
                        header("Authorization", altAuthHeader)
                        contentType(ContentType.Application.Json)
                        setBody("""{"media_id":"$mediaId","alt_text":{"text":"${file.altText}"}}""")
                    }
                }

                mediaId.right()
            } else {
                val errorBody = response.bodyAsText()
                logger.warn {
                    "Failed to upload media to Twitter: ${response.status}, body: $errorBody"
                }
                RequestError(
                        status = response.status.value,
                        module = "twitter",
                        errorMessage = "Failed to upload media",
                        body = ResponseBody(asString = errorBody),
                    )
                    .left()
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to upload media (twitter) — uuid $uuid" }
            CaughtException(
                    status = 500,
                    module = "twitter",
                    errorMessage = "Failed to upload media — uuid: $uuid",
                )
                .left()
        }
    }

    /** Create a post on Twitter */
    suspend fun createPost(request: NewPostRequest): ApiResult<NewPostResponse> {
        return try {
            // Validate request
            request.validate()?.let { error ->
                return error.left()
            }

            // Get OAuth token
            val token =
                restoreOauthTokenFromDb()
                    ?: return ValidationError(
                            status = 401,
                            errorMessage = "Unauthorized: Missing Twitter OAuth token!",
                            module = "twitter",
                        )
                        .left()

            // Upload images if present
            val mediaIds = mutableListOf<String>()
            if (!request.images.isNullOrEmpty()) {
                for (imageUuid in request.images) {
                    when (val result = uploadMedia(token, imageUuid)) {
                        is Either.Right -> mediaIds.add(result.value)
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

            logger.info { "Posting to Twitter:\n${text.trim().prependIndent("  |")}" }

            // Create the tweet
            val createPostURL = "${config.apiBase}/2/tweets"
            val authHeader = signRequest(createPostURL, token)

            val tweetData =
                if (mediaIds.isNotEmpty()) {
                    TwitterCreateRequest(text = text, media = TwitterMedia(media_ids = mediaIds))
                } else {
                    TwitterCreateRequest(text = text)
                }

            val response =
                httpClient.post(createPostURL) {
                    header("Authorization", authHeader)
                    contentType(ContentType.Application.Json)
                    accept(ContentType.Application.Json)
                    setBody(Json.encodeToString(TwitterCreateRequest.serializer(), tweetData))
                }

            if (response.status.value == 201) {
                val data = response.body<TwitterPostResponse>()
                NewTwitterPostResponse(id = data.data.id).right()
            } else {
                val errorBody = response.bodyAsText()
                logger.warn { "Failed to post to Twitter: ${response.status}, body: $errorBody" }
                RequestError(
                        status = response.status.value,
                        module = "twitter",
                        errorMessage = "Failed to create post",
                        body = ResponseBody(asString = errorBody),
                    )
                    .left()
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to post to Twitter" }
            CaughtException(
                    status = 500,
                    module = "twitter",
                    errorMessage = "Failed to post to Twitter: ${e.message}",
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
        val token = call.request.queryParameters["oauth_token"]
        val verifier = call.request.queryParameters["oauth_verifier"]

        if (token == null || verifier == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "Invalid request"))
            return
        }

        logger.info { "Twitter auth callback: token=$token, verifier=$verifier" }

        when (val result = saveOauthToken(token, verifier)) {
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
                val error = result.value
                call.respond(
                    HttpStatusCode.fromValue(error.status),
                    ErrorResponse(error = error.errorMessage),
                )
            }
        }
    }

    /** Handle status check HTTP route */
    suspend fun statusRoute(call: ApplicationCall) {
        val row = documentsDb.searchByKey("twitter-oauth-token").getOrElse { throw it }
        call.respond(
            TwitterStatusResponse(
                hasAuthorization = row != null,
                createdAt = row?.createdAt?.toEpochMilli(),
            )
        )
    }

    /** Handle Twitter post creation HTTP route */
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
        // Use Jsoup to properly parse and clean HTML
        // This removes all HTML tags while preserving text content
        val text = Jsoup.parse(html).text()

        // Normalize whitespace
        return text.replace(Regex("\\s+"), " ").trim()
    }
}
