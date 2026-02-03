package socialpublish.backend.server.routes

import arrow.core.Either
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import socialpublish.backend.common.ErrorResponse
import socialpublish.backend.common.NewPostRequest
import socialpublish.backend.modules.RssModule

/** Admin user UUID used for public RSS feeds */
private val ADMIN_USER_UUID = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001")

class RssRoutes(private val rssModule: RssModule) {
    /** Handle RSS post creation HTTP route */
    suspend fun createPostRoute(call: ApplicationCall) {
        val userUuid = call.getAuthenticatedUserUuid()
        if (userUuid == null) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Unauthorized"))
            return
        }

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
                    NewPostRequest(
                        content = params?.get("content") ?: "",
                        targets = params?.getAll("targets"),
                        link = params?.get("link"),
                        language = params?.get("language"),
                        cleanupHtml = params?.get("cleanupHtml")?.toBoolean(),
                        images = params?.getAll("images"),
                    )
                }

        when (val result = rssModule.createPost(userUuid, request)) {
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

    /** Handle RSS feed generation HTTP route */
    suspend fun generateRssRoute(call: ApplicationCall) {
        val target = call.parameters["target"]
        val filterByLinks = call.request.queryParameters["filterByLinks"]
        val filterByImages = call.request.queryParameters["filterByImages"]

        val rssContent =
            rssModule.generateRss(ADMIN_USER_UUID, filterByLinks, filterByImages, target)
        call.respondText(rssContent, ContentType.Application.Rss)
    }

    /** Get RSS item by UUID */
    suspend fun getRssItem(call: ApplicationCall) {
        val uuid =
            call.parameters["uuid"]
                ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "Missing UUID"))
                    return
                }

        val post = rssModule.getRssItemByUuid(ADMIN_USER_UUID, uuid)
        if (post == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse(error = "Post not found"))
            return
        }

        call.respond(post)
    }
}
