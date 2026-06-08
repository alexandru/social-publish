package socialpublish.backend.server.routes

import arrow.core.Either
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import socialpublish.backend.clients.bluesky.BlueskyApiModule
import socialpublish.backend.clients.bluesky.BlueskyConfig
import socialpublish.backend.common.ErrorResponse
import socialpublish.backend.db.UserSession

class BlueskyRoutes(private val blueskyModule: BlueskyApiModule) {
    context(_: UserSession)
    suspend fun createPostRoute(
        blueskyConfig: BlueskyConfig,
        call: ApplicationCall,
    ) {
        val request = call.receiveNewPostRequestOrRespond() ?: return

        when (val result = blueskyModule.createThread(blueskyConfig, request)) {
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
