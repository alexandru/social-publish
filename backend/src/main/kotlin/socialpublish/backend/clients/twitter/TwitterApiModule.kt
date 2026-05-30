@file:Suppress("PropertyName")

package socialpublish.backend.clients.twitter

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.raise.context.bind
import arrow.core.raise.either
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import socialpublish.backend.common.*
import socialpublish.backend.common.jsonCommon
import socialpublish.backend.common.rethrowIfFatalOrCancelled
import socialpublish.backend.db.DBException
import socialpublish.backend.db.DocumentsDatabase
import socialpublish.backend.db.UUIDv7
import socialpublish.backend.db.UserSession
import socialpublish.backend.modules.FilesModule
import socialpublish.backend.server.userUuid

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
                        install(ContentNegotiation) { json(jsonCommon) }
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
            TwitterApiModule(
                baseUrl,
                documentsDb,
                filesModule,
                defaultHttpClient().bind(),
            )
        }
    }

    private class TwitterApi(private val config: TwitterConfig) :
        DefaultApi10a() {
        override fun getRequestTokenEndpoint() = config.oauthRequestTokenUrl

        override fun getAccessTokenEndpoint() = config.oauthAccessTokenUrl

        override fun getAuthorizationBaseUrl() = config.oauthAuthorizeUrl
    }

    private fun createOAuthService(
        config: TwitterConfig,
        callback: String? = null,
    ): OAuth10aService {
        val builder =
            ServiceBuilder(config.oauth1ConsumerKey)
                .apiSecret(config.oauth1ConsumerSecret)
        if (callback != null) {
            builder.callback(callback)
        }
        return builder.build(TwitterApi(config))
    }

    private val callbackUrl = "$baseUrl/api/twitter/callback"

    /** Parse payload to check for a valid access token without DB lookups. */
    fun hasValidAccessToken(payload: String): Boolean =
        TwitterOAuthDocument.parse(payload).fold({ false }) {
            it.accessToken != null
        }

    /** Restore OAuth token from database (scoped to the user) */
    private suspend fun restoreOauthTokenFromDb(
        userUuid: UUIDv7
    ): ApiResult<TwitterOAuthToken?> =
        when (val document = loadTwitterOAuthDocument(userUuid)) {
            is Either.Right -> document.value.accessToken.right()
            is Either.Left -> document.value.toTwitterApiError().left()
        }

    /** Load the full OAuth document, or return an empty one if none exists. */
    private suspend fun loadTwitterOAuthDocument(
        userUuid: UUIDv7
    ): Either<DBException, TwitterOAuthDocument> = either {
        val doc =
            documentsDb
                .searchByKey("twitter-oauth-token:$userUuid", userUuid)
                .bind()
        if (doc == null) {
            TwitterOAuthDocument()
        } else {
            TwitterOAuthDocument.parse(doc.payload)
                .mapLeft {
                    DBException(
                        "Invalid Twitter OAuth document payload for user $userUuid",
                        it,
                    )
                }
                .bind()
        }
    }

    /** Save OAuth document to database */
    private suspend fun saveTwitterOAuthDocument(
        userUuid: UUIDv7,
        document: TwitterOAuthDocument,
    ): Either<DBException, Unit> = either {
        val _ =
            documentsDb
                .createOrUpdate(
                    kind = "twitter-oauth-token",
                    payload = document.toJson(),
                    userUuid = userUuid,
                    searchKey = "twitter-oauth-token:$userUuid",
                    tags = emptyList(),
                )
                .bind()
        Unit
    }

    private fun DBException.toTwitterApiError(): ApiError {
        logger.error(this) { "Twitter OAuth database error" }
        return CaughtException(
            status = 500,
            module = "twitter",
            errorMessage = "Twitter OAuth database error",
        )
    }

    /** Build authorization URL for OAuth flow */
    suspend fun buildAuthorizeURL(
        config: TwitterConfig,
        userUuid: UUIDv7,
    ): ApiResult<String> {
        return try {
            val service = createOAuthService(config, callbackUrl)
            val token = withContext(Dispatchers.LoomIO) { service.requestToken }
            val authUrl = service.getAuthorizationUrl(token)

            // Store pending request token in existing auth document
            val existingDoc =
                loadTwitterOAuthDocument(userUuid).getOrElse {
                    return it.toTwitterApiError().left()
                }
            saveTwitterOAuthDocument(
                    userUuid,
                    existingDoc.copy(
                        pendingRequest =
                            TwitterOAuthRequestToken(
                                token = token.token,
                                secret = token.tokenSecret,
                            )
                    ),
                )
                .getOrElse {
                    return it.toTwitterApiError().left()
                }

            authUrl.right()
        } catch (e: Throwable) {
            rethrowIfFatalOrCancelled(e)
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
        userUuid: UUIDv7,
    ): ApiResult<Unit> {
        return try {
            val existingDoc =
                loadTwitterOAuthDocument(userUuid).getOrElse {
                    return it.toTwitterApiError().left()
                }
            val pending = existingDoc.pendingRequest

            if (pending == null) {
                logger.warn {
                    "No pending request token found for user $userUuid"
                }
                return RequestError(
                        status = 400,
                        module = "twitter",
                        errorMessage =
                            "Authorization could not be verified. Please try again.",
                    )
                    .left()
            }

            if (pending.token != token) {
                logger.warn {
                    "Callback token mismatch: expected ${pending.token}, got $token"
                }
                return RequestError(
                        status = 400,
                        module = "twitter",
                        errorMessage =
                            "Authorization could not be verified. Please try again.",
                    )
                    .left()
            }

            val reqToken = OAuth1RequestToken(token, pending.secret)
            val service = createOAuthService(config)
            val accessToken =
                withContext(Dispatchers.LoomIO) {
                    service.getAccessToken(reqToken, verifier)
                }

            val authorizedToken =
                TwitterOAuthToken(
                    key = accessToken.token,
                    secret = accessToken.tokenSecret,
                )

            saveTwitterOAuthDocument(
                    userUuid,
                    existingDoc.copy(
                        accessToken = authorizedToken,
                        pendingRequest = null,
                    ),
                )
                .getOrElse {
                    return it.toTwitterApiError().left()
                }

            Unit.right()
        } catch (e: Throwable) {
            rethrowIfFatalOrCancelled(e)
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
    context(_: UserSession)
    private suspend fun uploadMedia(
        config: TwitterConfig,
        token: TwitterOAuthToken,
        uuid: String,
    ): ApiResult<String> = resourceScope {
        try {
            val file =
                filesModule.readImageFile(uuid)
                    ?: return@resourceScope ValidationError(
                            status = 404,
                            errorMessage =
                                "Failed to read image file — uuid: $uuid",
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
                                    append(
                                        HttpHeaders.ContentType,
                                        file.mimetype,
                                    )
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
                    val altTextUrl =
                        "${config.apiBase}/1.1/media/metadata/create.json"
                    val altAuthHeader = signRequest(config, altTextUrl, token)

                    httpClient.post(altTextUrl) {
                        header("Authorization", altAuthHeader)
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"media_id":"$mediaId","alt_text":{"text":"${file.altText}"}}"""
                        )
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
        } catch (e: Throwable) {
            rethrowIfFatalOrCancelled(e)
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
    context(_: UserSession)
    suspend fun createPost(
        config: TwitterConfig,
        request: NewPostRequest,
    ): ApiResult<NewPostResponse> {
        return try {
            val userUuid = userUuid()
            // Validate request
            request.validate()?.let { error ->
                return error.left()
            }

            // Get OAuth token
            val token =
                restoreOauthTokenFromDb(userUuid).getOrElse {
                    return it.left()
                }
                    ?: return ValidationError(
                            status = 401,
                            errorMessage =
                                "Unauthorized: Missing Twitter OAuth token!",
                            module = "twitter",
                        )
                        .left()

            // Upload images if present
            val mediaIds = mutableListOf<String>()
            if (!request.images.isNullOrEmpty()) {
                for (imageUuid in request.images) {
                    mediaIds.add(
                        uploadMedia(config, token, imageUuid).getOrElse {
                            return it.left()
                        }
                    )
                }
            }

            // Prepare text
            val text =
                if (request.cleanupHtml == true) {
                    cleanupHtml(request.content)
                } else {
                    request.content.trim()
                } + if (request.link != null) "\n\n${request.link}" else ""

            logger.info {
                "Posting to Twitter:\n${text.trim().prependIndent("  |")}"
            }

            // Create the tweet
            val createPostURL = "${config.apiBase}/2/tweets"
            val authHeader = signRequest(config, createPostURL, token)

            val tweetData =
                if (mediaIds.isNotEmpty()) {
                    TwitterCreateRequest(
                        text = text,
                        media = TwitterMedia(media_ids = mediaIds),
                    )
                } else {
                    TwitterCreateRequest(text = text)
                }

            val response =
                httpClient.post(createPostURL) {
                    header("Authorization", authHeader)
                    contentType(ContentType.Application.Json)
                    accept(ContentType.Application.Json)
                    setBody(
                        Json.encodeToString(
                            TwitterCreateRequest.serializer(),
                            tweetData,
                        )
                    )
                }

            if (response.status.value == 201) {
                val data = response.body<TwitterPostResponse>()
                NewTwitterPostResponse(id = data.data.id).right()
            } else {
                val errorBody = response.bodyAsText()
                logger.warn {
                    "Failed to post to Twitter: ${response.status}, body: $errorBody"
                }
                RequestError(
                        status = response.status.value,
                        module = "twitter",
                        errorMessage = "Failed to create post",
                        body = ResponseBody(asString = errorBody),
                    )
                    .left()
            }
        } catch (e: Throwable) {
            rethrowIfFatalOrCancelled(e)
            logger.error(e) { "Failed to post to Twitter" }
            CaughtException(
                    status = 500,
                    module = "twitter",
                    errorMessage = "Failed to post to Twitter: ${e.message}",
                )
                .left()
        }
    }

    private fun cleanupHtml(html: String): String {
        val text = Jsoup.parse(html).text()
        return text.replace(Regex("\\s+"), " ").trim()
    }
}
