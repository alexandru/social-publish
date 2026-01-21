package socialpublish.backend.integrations.linkedin

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
import io.ktor.client.request.accept
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import socialpublish.backend.db.DocumentsDatabase
import socialpublish.backend.models.ApiResult
import socialpublish.backend.models.CaughtException
import socialpublish.backend.models.NewPostRequest
import socialpublish.backend.models.NewPostResponse
import socialpublish.backend.models.NewLinkedinPostResponse
import socialpublish.backend.models.RequestError
import socialpublish.backend.models.ResponseBody
import socialpublish.backend.models.ValidationError
import socialpublish.backend.modules.FilesModule

private val logger = KotlinLogging.logger {}

@Serializable
data class LinkedinToken(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresIn: Long,
)

@Serializable
data class LinkedinImageRegisterResponse(@SerialName("value") val value: LinkedinAssetValue)

@Serializable
data class LinkedinAssetValue(
    @SerialName("uploadMechanism") val uploadMechanism: LinkedinUploadMechanism,
    @SerialName("asset") val asset: String,
)

@Serializable
data class LinkedinUploadMechanism(
    @SerialName("com.linkedin.digitalmedia.uploading.MediaUploadHttpRequest")
    val request: LinkedinUploadRequest,
)

@Serializable
data class LinkedinUploadRequest(
    @SerialName("uploadUrl") val uploadUrl: String,
    @SerialName("headers") val headers: Map<String, String> = emptyMap(),
)

@Serializable
data class LinkedinShareResponse(@SerialName("id") val id: String) : NewPostResponse() {
    override val module: String = "linkedin"
}

class LinkedInApiModule(
    private val config: LinkedInConfig,
    private val documentsDb: DocumentsDatabase,
    private val filesModule: FilesModule,
    private val httpClient: HttpClient = defaultHttpClient(),
) {
    companion object {
        fun defaultHttpClient(): HttpClient =
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

        fun resource(
            config: LinkedInConfig,
            documentsDb: DocumentsDatabase,
            filesModule: FilesModule,
        ): Resource<LinkedInApiModule> = resource {
            val client = install({ defaultHttpClient() }, { client, _ -> client.close() })
            LinkedInApiModule(config, documentsDb, filesModule, client)
        }
    }

    private suspend fun getToken(): ApiResult<String> {
        val doc = documentsDb.searchByKey("linkedin-token")
        return if (doc != null) {
            try {
                val token = Json.decodeFromString<LinkedinToken>(doc.payload)
                token.accessToken.right()
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse LinkedIn token" }
                ValidationError(
                        status = 401,
                        module = "linkedin",
                        errorMessage = "LinkedIn access token invalid",
                    )
                    .left()
            }
        } else {
            ValidationError(
                    status = 401,
                    module = "linkedin",
                    errorMessage = "LinkedIn not authorized",
                )
                .left()
        }
    }

    suspend fun saveToken(token: LinkedinToken): ApiResult<Unit> {
        return try {
            documentsDb.createOrUpdate(
                kind = "linkedin-token",
                payload = Json.encodeToString(LinkedinToken.serializer(), token),
                searchKey = "linkedin-token",
                tags = emptyList(),
            )
            Unit.right()
        } catch (e: Exception) {
            CaughtException(
                    status = 500,
                    module = "linkedin",
                    errorMessage = "Failed to persist LinkedIn token: ${e.message}",
                )
                .left()
        }
    }

    suspend fun hasLinkedinAuth(): Boolean = getToken().isRight()

    private suspend fun registerUpload(auth: String, altText: String?): ApiResult<LinkedinUploadRequest> {
        return try {
            val response =
                httpClient.post("https://api.linkedin.com/rest/images?action=initializeUpload") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $auth")
                        append("X-Restli-Protocol-Version", "2.0.0")
                        append(HttpHeaders.Accept, "application/json")
                    }
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "initializeUploadRequest": {
                            "owner": "${config.authorUrn}"${
                                altText?.let { """, "recipes": ["urn:li:digitalmediaRecipe:feedshare-image"]""" }
                                    ?: ""
                            }
                          }
                        }
                        """
                            .trimIndent()
                    )
                }

            if (response.status != HttpStatusCode.OK) {
                val body = response.bodyAsText()
                return RequestError(
                        status = response.status.value,
                        module = "linkedin",
                        errorMessage = "Failed to initialize upload",
                        body = ResponseBody(asString = body),
                    )
                    .left()
            }

            val data = response.body<LinkedinImageRegisterResponse>()
            data.value.uploadMechanism.request.right()
        } catch (e: Exception) {
            logger.error(e) { "Failed to initialize LinkedIn upload" }
            CaughtException(
                    status = 500,
                    module = "linkedin",
                    errorMessage = "Failed to initialize upload: ${e.message}",
                )
                .left()
        }
    }

    private suspend fun uploadImage(request: LinkedinUploadRequest, bytes: ByteArray, mime: String): ApiResult<String> {
        return try {
            val response =
                httpClient.post(request.uploadUrl) {
                    contentType(ContentType.parse(mime))
                    setBody(bytes)
                    request.headers.forEach { (k, v) -> headers { append(k, v) } }
                }

            if (response.status.isSuccess()) {
                request.uploadUrl.right()
            } else {
                RequestError(
                        status = response.status.value,
                        module = "linkedin",
                        errorMessage = "Failed to upload image",
                        body = ResponseBody(asString = response.bodyAsText()),
                    )
                    .left()
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to upload to LinkedIn" }
            CaughtException(
                    status = 500,
                    module = "linkedin",
                    errorMessage = "Failed to upload image: ${e.message}",
                )
                .left()
        }
    }

    private suspend fun createShare(
        auth: String,
        text: String,
        mediaAsset: String?,
        link: String?,
        altText: String?,
    ): ApiResult<NewPostResponse> {
        return try {
            val mediaBlock =
                mediaAsset?.let {
                    """
                    "media": [
                      {
                        "status": "READY",
                        "media": "$it"${
                            altText?.let { alt -> """, "description": { "text": "$alt" }""" } ?: ""
                        }
                      }
                    ],
                    """
                        .trimIndent()
                }
                    ?: ""

            val linkBlock =
                link?.let {
                    """
                    "content": {
                      "article": {
                        "source": "$it"
                      }
                    },
                    """
                        .trimIndent()
                }
                    ?: ""

            val body =
                """
                {
                  "owner": "${config.authorUrn}",
                  "text": { "text": "$text" },
                  $linkBlock
                  ${mediaBlock.trimIndent()}
                  "distribution": { "feedDistribution": "MAIN_FEED" },
                  "subject": "Post"
                }
                """
                    .trimIndent()

            val response =
                httpClient.post("https://api.linkedin.com/v2/ugcPosts") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $auth")
                        append("X-Restli-Protocol-Version", "2.0.0")
                        append(HttpHeaders.Accept, "application/json")
                    }
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }

            if (response.status == HttpStatusCode.Created || response.status == HttpStatusCode.OK) {
                val data = response.body<LinkedinShareResponse>()
                data.right()
            } else {
                RequestError(
                        status = response.status.value,
                        module = "linkedin",
                        errorMessage = "Failed to create LinkedIn post",
                        body = ResponseBody(asString = response.bodyAsText()),
                    )
                    .left()
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to create LinkedIn post" }
            CaughtException(
                    status = 500,
                    module = "linkedin",
                    errorMessage = "Failed to create LinkedIn post: ${e.message}",
                )
                .left()
        }
    }

    suspend fun createPost(request: NewPostRequest): ApiResult<NewPostResponse> {
        request.validate()?.let { return it.left() }

        val auth = when (val token = getToken()) {
            is Either.Right -> token.value
            is Either.Left -> return token.value.left()
        }

        val text =
            (if (request.cleanupHtml == true) request.content.replace(Regex("<.*?>"), " ")
                .trim() else request.content.trim()) +
                if (request.link != null) "\n\n${request.link}" else ""

        var asset: String? = null
        var alt: String? = null
        if (!request.images.isNullOrEmpty()) {
            val imageUuid = request.images.first()
            val file =
                filesModule.readImageFile(imageUuid, maxWidth = 1200, maxHeight = 1200)
                    ?: return ValidationError(
                            status = 404,
                            module = "linkedin",
                            errorMessage = "Image not found: $imageUuid",
                        )
                        .left()
            alt = file.altText
            val register =
                when (val reg = registerUpload(auth, file.altText)) {
                    is Either.Right -> reg.value
                    is Either.Left -> return reg.value.left()
                }

            when (val upload = uploadImage(register, file.bytes, file.mimetype)) {
                is Either.Left -> return upload.value.left()
                is Either.Right -> {
                    asset = register.uploadUrl.substringBefore("?").replace("https://api.linkedin.com/media/", "urn:li:digitalmediaAsset:")
                }
            }
        }

        return createShare(auth, text, asset, request.link, alt)
    }
}
