package socialpublish.backend.server.routes

import arrow.core.Either
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import java.util.UUID
import socialpublish.backend.clients.llm.GenerateAltTextRequest
import socialpublish.backend.clients.llm.GenerateAltTextResponse
import socialpublish.backend.clients.llm.LlmApiModule
import socialpublish.backend.clients.llm.LlmConfig
import socialpublish.backend.common.ErrorResponse
import socialpublish.backend.server.respondWithNotConfigured

class LlmRoutes(private val llmModule: LlmApiModule) {
    suspend fun generateAltTextRoute(userUuid: UUID, llmConfig: LlmConfig?, call: ApplicationCall) {
        if (llmConfig == null) {
            call.respondWithNotConfigured("LLM")
            return
        }
        val request =
            runCatching { call.receive<GenerateAltTextRequest>() }.getOrNull()
                ?: run {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(error = "Invalid request body"),
                    )
                    return
                }
        when (
            val result = llmModule.generateAltText(llmConfig, request.imageUuid, request.userContext, request.language)
        ) {
            is Either.Right -> call.respond(GenerateAltTextResponse(altText = result.value))
            is Either.Left -> {
                val error = result.value
                call.respond(
                    HttpStatusCode.fromValue(error.status),
                    ErrorResponse(
                        error =
                            error.errorMessage +
                                if (error.module == "llm") " (llm integration)" else ""
                    ),
                )
            }
        }
    }
}
