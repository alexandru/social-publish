package com.alexn.socialpublish.modules

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.alexn.socialpublish.config.AppConfig
import com.alexn.socialpublish.models.*
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val logger = KotlinLogging.logger {}

@Serializable
data class MastodonMediaResponse(
    val id: String,
    val url: String? = null,
    val preview_url: String? = null,
    val description: String? = null
)

@Serializable
data class MastodonStatusResponse(
    val id: String,
    val uri: String,
    val url: String
)

class MastodonApiModule(
    private val config: AppConfig,
    private val filesModule: FilesModule
) {
    
    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
    }
    
    private val mediaUrlV2 = "${config.mastodonHost}/api/v2/media"
    private val mediaUrlV1 = "${config.mastodonHost}/api/v1/media"
    private val statusesUrlV1 = "${config.mastodonHost}/api/v1/statuses"
    
    /**
     * Upload media to Mastodon
     */
    private suspend fun uploadMedia(uuid: String): ApiResult<MastodonMediaResponse> {
        return try {
            val file = filesModule.readImageFile(uuid)
                ?: return ValidationError(
                    status = 404,
                    errorMessage = "Failed to read image file — uuid: $uuid",
                    module = "mastodon"
                ).left()
            
            val response = httpClient.submitFormWithBinaryData(
                url = mediaUrlV2,
                formData = formData {
                    append("file", file.bytes, Headers.build {
                        append(HttpHeaders.ContentType, file.mimetype)
                        append(HttpHeaders.ContentDisposition, "filename=\"${file.originalname}\"")
                    })
                    file.altText?.let { append("description", it) }
                }
            ) {
                header("Authorization", "Bearer ${config.mastodonAccessToken}")
            }
            
            when (response.status.value) {
                200 -> {
                    val data = response.body<MastodonMediaResponse>()
                    data.right()
                }
                202 -> {
                    // Async upload - poll for completion
                    val initialData = response.body<MastodonMediaResponse>()
                    waitForMediaProcessing(initialData.id)
                }
                else -> {
                    val errorBody = response.bodyAsText()
                    logger.warn { "Failed to upload media to Mastodon: ${response.status}, body: $errorBody" }
                    RequestError(
                        status = response.status.value,
                        module = "mastodon",
                        errorMessage = "Failed to upload media",
                        body = ResponseBody(asString = errorBody)
                    ).left()
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to upload media (mastodon) — uuid $uuid" }
            CaughtException(
                status = 500,
                module = "mastodon",
                errorMessage = "Failed to upload media — uuid: $uuid"
            ).left()
        }
    }
    
    /**
     * Poll for media processing completion
     */
    private suspend fun waitForMediaProcessing(mediaId: String): ApiResult<MastodonMediaResponse> {
        for (attempt in 1..30) { // Try for up to 6 seconds
            delay(200)
            
            val response = httpClient.get("$mediaUrlV1/$mediaId") {
                header("Authorization", "Bearer ${config.mastodonAccessToken}")
            }
            
            when (response.status.value) {
                200 -> {
                    val data = response.body<MastodonMediaResponse>()
                    return data.right()
                }
                202 -> {
                    // Still processing, continue polling
                    continue
                }
                else -> {
                    val errorBody = response.bodyAsText()
                    return RequestError(
                        status = response.status.value,
                        module = "mastodon",
                        errorMessage = "Failed to get media status",
                        body = ResponseBody(asString = errorBody)
                    ).left()
                }
            }
        }
        
        return CaughtException(
            status = 500,
            module = "mastodon",
            errorMessage = "Media processing timeout"
        ).left()
    }
    
    /**
     * Create a post on Mastodon
     */
    suspend fun createPost(request: NewPostRequest): ApiResult<NewPostResponse> {
        return try {
            // Validate request
            request.validate()?.let { error ->
                return error.left()
            }
            
            // Upload images if present
            val mediaIds = mutableListOf<String>()
            if (!request.images.isNullOrEmpty()) {
                for (imageUuid in request.images) {
                    when (val result = uploadMedia(imageUuid)) {
                        is Either.Right -> mediaIds.add(result.value.id)
                        is Either.Left -> return result.value.left()
                    }
                }
            }
            
            // Prepare status content
            val content = if (request.cleanupHtml == true) {
                cleanupHtml(request.content)
            } else {
                request.content
            } + if (request.link != null) "\n\n${request.link}" else ""
            
            logger.info { "Posting to Mastodon:\n${content.trim().prependIndent("  |")}" }
            
            // Create status
            val response = httpClient.submitForm(
                url = statusesUrlV1,
                formParameters = parameters {
                    append("status", content)
                    if (mediaIds.isNotEmpty()) {
                        mediaIds.forEach { append("media_ids[]", it) }
                    }
                    request.language?.let { append("language", it) }
                }
            ) {
                header("Authorization", "Bearer ${config.mastodonAccessToken}")
            }
            
            if (response.status.value == 200) {
                val data = response.body<MastodonStatusResponse>()
                NewMastodonPostResponse(
                    uri = data.url
                ).right()
            } else {
                val errorBody = response.bodyAsText()
                logger.warn { "Failed to post to Mastodon: ${response.status}, body: $errorBody" }
                RequestError(
                    status = response.status.value,
                    module = "mastodon",
                    errorMessage = "Failed to create status",
                    body = ResponseBody(asString = errorBody)
                ).left()
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to post to Mastodon" }
            CaughtException(
                status = 500,
                module = "mastodon",
                errorMessage = "Failed to post to Mastodon: ${e.message}"
            ).left()
        }
    }
    
    /**
     * Handle Mastodon post creation HTTP route
     */
    suspend fun createPostRoute(call: ApplicationCall) {
        val params = call.receiveParameters()
        val request = NewPostRequest(
            content = params["content"] ?: "",
            targets = params.getAll("targets[]"),
            link = params["link"],
            language = params["language"],
            cleanupHtml = params["cleanupHtml"]?.toBoolean(),
            images = params.getAll("images[]")
        )
        
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
