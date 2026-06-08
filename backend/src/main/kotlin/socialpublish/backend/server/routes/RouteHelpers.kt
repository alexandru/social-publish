package socialpublish.backend.server.routes

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.raise.context.bind
import arrow.core.raise.either
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import java.net.URLEncoder
import socialpublish.backend.common.ErrorResponse
import socialpublish.backend.common.NewPostRequest

suspend fun ApplicationCall.receiveNewPostRequest():
    Either<ErrorResponse, NewPostRequest> =
    runCatching { receive<NewPostRequest>() }.getOrNull()?.let { either { it } }
        ?: receiveParameters().let { params ->
            NewPostRequest.singleMessageFromTargetNames(
                content = params["content"] ?: "",
                targets = params.getAll("targets"),
                link = params["link"],
                language = params["language"],
                images = params.getAll("images"),
            )
        }

suspend fun ApplicationCall.receiveNewPostRequestOrRespond(): NewPostRequest? =
    either { receiveNewPostRequest().bind() }
        .getOrElse { error ->
            respond(HttpStatusCode.BadRequest, error)
            null
        }

fun ApplicationCall.preventOAuthRedirectCaching() {
    response.header("Cache-Control", "no-store, no-cache, must-revalidate, private")
    response.header("Pragma", "no-cache")
    response.header("Expires", "0")
}

private suspend fun ApplicationCall.redirectWithParam(
    key: String,
    value: String,
) {
    respondRedirect(
        "/account?$key=${URLEncoder.encode(value, Charsets.UTF_8)}"
    )
}

suspend fun ApplicationCall.redirectToAccountError(message: String) {
    redirectWithParam("error", message)
}

suspend fun ApplicationCall.redirectToAccountInfo(message: String) {
    redirectWithParam("info", message)
}
