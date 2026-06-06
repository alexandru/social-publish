package socialpublish.backend.server.routes

import arrow.core.Either
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import socialpublish.backend.common.CompositeError
import socialpublish.backend.common.CompositeErrorWithDetails
import socialpublish.backend.common.ErrorResponse
import socialpublish.backend.modules.PublishModule

class PublishRoutes {
    /** Handle broadcast POST HTTP route */
    suspend fun broadcastPostRoute(
        call: ApplicationCall,
        publishModule: PublishModule,
    ) {
        val request = call.receiveNewPostRequest()

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
