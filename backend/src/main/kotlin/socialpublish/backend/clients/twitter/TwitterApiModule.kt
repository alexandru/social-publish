@file:Suppress("PropertyName")

package socialpublish.backend.clients.twitter

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import arrow.fx.coroutines.Resource
import arrow.fx.coroutines.resource
import arrow.fx.coroutines.resourceScope
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
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import socialpublish.backend.common.*
import socialpublish.backend.db.DocumentsDatabase
import socialpublish.backend.modules.FilesModule

private val logger = KotlinLogging.logger {}

/** Twitter API module with OAuth 1.0a implementation */
class TwitterApiModule(
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
            baseUrl: String,
            documentsDb: DocumentsDatabase,
            filesModule: FilesModule,
        ): Resource<TwitterApiModule> = resource {
            TwitterApiModule(baseUrl, documentsDb, filesModule, defaultHttpClient().bind())
        }
    }

    private class TwitterApi(private val config: TwitterConfig) : DefaultApi10a() {
        override fun getRequestTokenEndpoint() = config.oauthRequestTokenUrl

        override fun getAccessTokenEndpoint() = config.oauthAccessTokenUrl

        override fun getAuthorizationBaseUrl() = config.oauthAuthorizeUrl
    }

    private fun createOAuthService(
        config: TwitterConfig,
        callback: String? = null,
    ): OAuth10aService {
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

    /** Check if Twitter auth exists for the given user */
    suspend fun hasTwitterAuth(userUuid: java.util.UUID): Boolean {
        val token = restoreOauthTokenFromDb(userUuid)
        return token != null
    }

    /** Restore OAuth token from database (scoped to the user) */
    private suspend fun restoreOauthTokenFromDb(userUuid: java.util.UUID): TwitterOAuthToken? {
        val doc =
            documentsDb.searchByKey("twitter-oauth-token:$userUuid", userUuid).getOrElse {
                throw it
            }
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
    suspend fun buildAuthorizeURL(config: TwitterConfig, jwtToken: String): ApiResult<String> {
        return try {
            val callbackUrl = getCallbackUrl(jwtToken)
            val service = createOAuthService(config, callbackUrl)
            val token = withContext(Dispatchers.LoomIO) { service.requestToken }
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

    /** Save OAuth token after callback (scoped to the user) */
    suspend fun saveOauthToken(
        config: TwitterConfig,
        token: String,
        verifier: String,
        userUuid: java.util.UUID,
    ): ApiResult<Unit> {
        return try {
            // Twitter's access token endpoint doesn't require the request token secret
            // in the OAuth signature, only the oauth_token and oauth_verifier parameters
            val reqToken = OAuth1RequestToken(token, "")
            val service = createOAuthService(config)
            val accessToken =
                withContext(Dispatchers.LoomIO) { service.getAccessToken(reqToken, verifier) }

            val authorizedToken =
                TwitterOAuthToken(key = accessToken.token, secret = accessToken.tokenSecret)

            val _ =
                documentsDb.createOrUpdate(
                    kind = "twitter-oauth-token",
                    payload = Json.encodeToString(TwitterOAuthToken.serializer(), authorizedToken),
                    userUuid = userUuid,
                    searchKey = "twitter-oauth-token:$userUuid",
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
        config: TwitterConfig,
        url: String,
        token: TwitterOAuthToken,
        verb: Verb = Verb.POST,
    ): String =
        withContext(Dispatchers.LoomIO) {
            val service = createOAuthService(config)
            val accessToken = OAuth1AccessToken(token.key, token.secret)
            val request = OAuthRequest(verb, url)
            service.signRequest(accessToken, request)
            request.headers["Authorization"]
                ?: throw IllegalStateException("Authorization header not found")
        }

    /** Upload media to Twitter */
    private suspend fun uploadMedia(
        config: TwitterConfig,
        token: TwitterOAuthToken,
        uuid: String,
        userUuid: java.util.UUID,
    ): ApiResult<String> = resourceScope {
        try {
            val file =
                filesModule.readImageFile(uuid, userUuid)
                    ?: return@resourceScope ValidationError(
                            status = 404,
                            errorMessage = "Failed to read image file — uuid: $uuid",
                            module = "twitter",
                        )
                        .left()

            val url = "${config.uploadBase}/1.1/media/upload.json"

            // Generate auth header by signing URL
            val authHeader = signRequest(config, url, token)

            val response =
                httpClient.submitFormWithBinaryData(
                    url = url,
                    formData =
                        formData {
                            append(
                                "media",
                                file.source.asKotlinSource().bind(),
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
                    val altAuthHeader = signRequest(config, altTextUrl, token)

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
    suspend fun createPost(
        config: TwitterConfig,
        request: NewPostRequest,
        userUuid: java.util.UUID,
        replyToId: String? = null,
    ): ApiResult<NewPostResponse> {
        return try {
            // Validate request
            request.validate()?.let { error ->
                return error.left()
            }

            val message = request.messages.first()

            // Get OAuth token
            val token =
                restoreOauthTokenFromDb(userUuid)
                    ?: return ValidationError(
                            status = 401,
                            errorMessage = "Unauthorized: Missing Twitter OAuth token!",
                            module = "twitter",
                        )
                        .left()

            // Upload images if present
            val mediaIds = mutableListOf<String>()
            if (!message.images.isNullOrEmpty()) {
                for (imageUuid in message.images) {
                    when (val result = uploadMedia(config, token, imageUuid, userUuid)) {
                        is Either.Right -> mediaIds.add(result.value)
                        is Either.Left -> return result.value.left()
                    }
                }
            }

            // Prepare text
            val text =
                message.content.trim() + if (message.link != null) "\n\n${message.link}" else ""

            logger.info { "Posting to Twitter:\n${text.trim().prependIndent("  |")}" }

            // Create the tweet
            val createPostURL = "${config.apiBase}/2/tweets"
            val authHeader = signRequest(config, createPostURL, token)

            val tweetData =
                TwitterCreateRequest(
                    text = text,
                    media = if (mediaIds.isNotEmpty()) TwitterMedia(media_ids = mediaIds) else null,
                    reply = replyToId?.let { TwitterReply(in_reply_to_tweet_id = it) },
                )

            val response =
                httpClient.post(createPostURL) {
                    header("Authorization", authHeader)
                    contentType(ContentType.Application.Json)
                    accept(ContentType.Application.Json)
                    setBody(Json.encodeToString(TwitterCreateRequest.serializer(), tweetData))
                }

            if (response.status.value == 201) {
                val data = response.body<TwitterPostResponse>()
                NewTwitterPostResponse(
                        id = data.data.id,
                        messages =
                            listOf(
                                PublishedMessageResponse(id = data.data.id, replyToId = replyToId)
                            ),
                    )
                    .right()
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

    suspend fun createThread(
        config: TwitterConfig,
        request: NewPostRequest,
        userUuid: java.util.UUID,
    ): ApiResult<NewPostResponse> {
        request.validate()?.let {
            return it.left()
        }

        var previousId: String? = null
        val messages = mutableListOf<PublishedMessageResponse>()
        var rootId = ""

        for (message in request.messages) {
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
                        replyToId = previousId,
                    )
            ) {
                is Either.Left -> return result.value.left()
                is Either.Right -> {
                    val response = result.value as NewTwitterPostResponse
                    if (messages.isEmpty()) {
                        rootId = response.id
                    }
                    val published = response.messages.first()
                    messages += published
                    previousId = published.id
                }
            }
        }

        return NewTwitterPostResponse(id = rootId, messages = messages).right()
    }
}
