package socialpublish.backend.server.routes

import arrow.core.Either
import arrow.core.nonEmptyListOf
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import socialpublish.backend.clients.mastodon.MastodonApiModule
import socialpublish.backend.clients.mastodon.MastodonConfig
import socialpublish.backend.common.ErrorResponse
import socialpublish.backend.common.NewPostRequest
import socialpublish.backend.common.NewPostRequestMessage
import socialpublish.backend.db.UserSession

class MastodonRoutes(private val mastodonModule: MastodonApiModule) {
    context(_: UserSession)
    suspend fun createPostRoute(
        mastodonConfig: MastodonConfig,
        call: ApplicationCall,
    ) {
        val request =
            runCatching { call.receive<NewPostRequest>() }.getOrNull()
                ?: run {
                    val contentTypeHeader =
                        call.request.headers[HttpHeaders.ContentType]
                    val contentType = contentTypeHeader?.let {
                        ContentType.parse(it)
                    }
                    val params =
                        if (
                            contentType?.match(
                                ContentType.Application.FormUrlEncoded
                            ) == true ||
                                contentType?.match(
                                    ContentType.MultiPart.FormData
                                ) == true
                        ) {
                            call.receiveParameters()
                        } else {
                            null
                        }
                    NewPostRequest(
                        targets = params?.getAll("targets"),
                        language = params?.get("language"),
                        messages =
                            nonEmptyListOf(
                                NewPostRequestMessage(
                                    content = params?.get("content") ?: "",
                                    link = params?.get("link"),
                                    images = params?.getAll("images"),
                                )
                            ),
                    )
                }

        when (
            val result = mastodonModule.createThread(mastodonConfig, request)
        ) {
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
}
