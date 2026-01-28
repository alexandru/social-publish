package socialpublish.backend.server.routes

import arrow.core.Either
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import socialpublish.backend.models.CompositeError
import socialpublish.backend.models.CompositeErrorWithDetails
import socialpublish.backend.models.ErrorResponse
import socialpublish.backend.models.NewPostRequest
import socialpublish.backend.modules.PublishModule

class PublishRoutes(private val publishModule: PublishModule) {
    /** Handle broadcast POST HTTP route */
    suspend fun broadcastPostRoute(call: ApplicationCall) {
        val request =
            runCatching { call.receive<NewPostRequest>() }.getOrNull()
                ?: run {
                    // If JSON receive failed, try form parameters. To avoid
                    // RequestAlreadyConsumedException,
                    // only attempt to read form parameters if content type is form data.
                    val contentTypeHeader = call.request.headers[HttpHeaders.ContentType]
                    val contentType = contentTypeHeader?.let { ContentType.parse(it) }
                    val params =
                        if (
                            contentType?.match(ContentType.Application.FormUrlEncoded) == true ||
                                contentType?.match(ContentType.MultiPart.FormData) == true
                        ) {
                            call.receiveParameters()
                        } else {
                            null
                        }
                    val targets = mutableListOf<String>()
                    params?.getAll("targets")?.let { targets.addAll(it) }

                    if (params?.get("mastodon") == "1") targets.add("mastodon")
                    if (params?.get("bluesky") == "1") targets.add("bluesky")
                    if (params?.get("twitter") == "1") targets.add("twitter")
                    if (params?.get("linkedin") == "1") targets.add("linkedin")
                    if (params?.get("rss") == "1") targets.add("rss")

                    NewPostRequest(
                        content = params?.get("content") ?: "",
                        targets = targets.ifEmpty { null },
                        link = params?.get("link"),
                        language = params?.get("language"),
                        cleanupHtml = params?.get("cleanupHtml")?.toBoolean(),
                        images = params?.getAll("images"),
                    )
                }

        when (val result = publishModule.broadcastPost(request)) {
            is Either.Right -> call.respond(result.value)
            is Either.Left -> {
                val error = result.value
                val payload =
                    if (error is CompositeError) {
                        CompositeErrorWithDetails(
                            error = error.errorMessage,
                            responses = error.responses,
                        )
                    } else {
                        ErrorResponse(error = error.errorMessage)
                    }
                call.respond(HttpStatusCode.fromValue(error.status), payload)
            }
        }
    }
}
