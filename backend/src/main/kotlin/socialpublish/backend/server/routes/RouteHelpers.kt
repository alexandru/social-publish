package socialpublish.backend.server.routes

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respondRedirect
import java.net.URLEncoder
import socialpublish.backend.common.NewPostRequest

suspend fun ApplicationCall.receiveNewPostRequest(): NewPostRequest =
    runCatching { receive<NewPostRequest>() }.getOrNull()
        ?: receiveParameters().let { params ->
            NewPostRequest(
                content = params["content"] ?: "",
                targets = params.getAll("targets"),
                link = params["link"],
                language = params["language"],
                cleanupHtml = params["cleanupHtml"]?.toBoolean(),
                images = params.getAll("images"),
            )
        }

fun ApplicationCall.preventOAuthRedirectCaching() {
    response.header(
        "Cache-Control",
        "no-store, no-cache, must-revalidate, private",
    )
    response.header("Pragma", "no-cache")
    response.header("Expires", "0")
}

suspend fun ApplicationCall.redirectToAccountError(message: String) {
    respondRedirect(
        "/account?error=${URLEncoder.encode(message, Charsets.UTF_8)}"
    )
}

suspend fun ApplicationCall.redirectToAccountInfo(message: String) {
    respondRedirect(
        "/account?info=${URLEncoder.encode(message, Charsets.UTF_8)}"
    )
}
