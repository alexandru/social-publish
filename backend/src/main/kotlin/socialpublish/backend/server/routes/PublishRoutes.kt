package socialpublish.backend.server.routes

import arrow.core.Either
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import socialpublish.backend.common.CompositeError
import socialpublish.backend.common.CompositeErrorWithDetails
import socialpublish.backend.common.ErrorResponse
import socialpublish.backend.common.NewPostRequest
import socialpublish.backend.common.NewPostRequestMessage
import socialpublish.backend.modules.PublishModule

class PublishRoutes {
    /** Handle broadcast POST HTTP route */
    suspend fun broadcastPostRoute(call: ApplicationCall, publishModule: PublishModule) {
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
                    if (params?.get("feed") == "1") targets.add("feed")

                    NewPostRequest(
                        targets = targets.ifEmpty { null },
                        language = params?.get("language"),
                        messages =
                            listOf(
                                NewPostRequestMessage(
                                    content = params?.get("content") ?: "",
                                    link = params?.get("link"),
                                    images = params?.getAll("images"),
                                )
                            ),
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
