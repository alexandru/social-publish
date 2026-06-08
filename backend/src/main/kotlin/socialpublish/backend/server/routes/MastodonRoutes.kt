package socialpublish.backend.server.routes

import arrow.core.Either
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import socialpublish.backend.clients.mastodon.MastodonApiModule
import socialpublish.backend.clients.mastodon.MastodonConfig
import socialpublish.backend.common.ErrorResponse
import socialpublish.backend.db.UserSession

class MastodonRoutes(private val mastodonModule: MastodonApiModule) {
    context(_: UserSession)
    suspend fun createPostRoute(
        mastodonConfig: MastodonConfig,
        call: ApplicationCall,
    ) {
        val request = call.receiveNewPostRequestOrRespond() ?: return

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
