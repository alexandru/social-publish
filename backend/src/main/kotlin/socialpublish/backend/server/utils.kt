package socialpublish.backend.server

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import socialpublish.backend.common.ErrorResponse

private val logger = KotlinLogging.logger {}

/** Respond with 500 Internal Server Error and log the exception. */
suspend fun ApplicationCall.respondWithInternalServerError(cause: Throwable, context: String = "") {
    val msg = if (context.isNotBlank()) context else "Server error"
    logger.error(cause) { msg }
    respond(HttpStatusCode.InternalServerError, ErrorResponse(error = "Server error"))
}

/** Respond with 404 Not Found for the named entity. */
suspend fun ApplicationCall.respondWithNotFound(entity: String = "Resource") {
    respond(HttpStatusCode.NotFound, ErrorResponse(error = "$entity not found"))
}

/** Respond with 403 Forbidden. */
suspend fun ApplicationCall.respondWithForbidden(message: String = "Forbidden") {
    respond(HttpStatusCode.Forbidden, ErrorResponse(error = message))
}

/** Respond with 401 Unauthorized. */
suspend fun ApplicationCall.respondWithUnauthorized() {
    respond(HttpStatusCode.Unauthorized, ErrorResponse(error = "Unauthorized"))
}

/** Respond with 503 Service Unavailable (integration not configured). */
suspend fun ApplicationCall.respondWithNotConfigured(integration: String) {
    respond(
        HttpStatusCode.ServiceUnavailable,
        ErrorResponse(error = "$integration integration not configured"),
    )
}
