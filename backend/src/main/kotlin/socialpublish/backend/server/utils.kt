package socialpublish.backend.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.util.AttributeKey
import socialpublish.backend.common.ApiError
import socialpublish.backend.common.ErrorResponse
import socialpublish.backend.common.loggerFactory
import socialpublish.backend.db.UUIDv7
import socialpublish.backend.db.UserSession
import socialpublish.backend.db.UserSettings

internal val UserUuidKey = AttributeKey<UUIDv7>("userUuid")
internal val UserSettingsKey = AttributeKey<UserSettings>("userSettings")
internal val UserSessionKey = AttributeKey<UserSession>("userSession")

internal fun ApplicationCall.putUserSession(session: UserSession) {
    attributes.put(UserSessionKey, session)
    attributes.put(UserUuidKey, session.user.uuid)
    attributes.put(UserSettingsKey, session.user.settings ?: UserSettings())
}

context(session: UserSession)
internal fun userUuid(): UUIDv7 = session.user.uuid

context(session: UserSession)
internal fun userSession(): UserSession = session

context(session: UserSession)
internal fun userSettings(): UserSettings =
    session.user.settings ?: UserSettings()

/** Respond with 500 Internal Server Error and log the exception. */
suspend fun ApplicationCall.respondWithInternalServerError(
    cause: Throwable,
    context: String = "",
) {
    val msg = if (context.isNotBlank()) context else "Server error"
    logger.error(msg, cause)
    respond(
        HttpStatusCode.InternalServerError,
        ErrorResponse(error = "Server error"),
    )
}

/** Respond with 404 Not Found for the named entity. */
suspend fun ApplicationCall.respondWithNotFound(entity: String = "Resource") {
    respond(HttpStatusCode.NotFound, ErrorResponse(error = "$entity not found"))
}

/** Respond with 403 Forbidden. */
suspend fun ApplicationCall.respondWithForbidden(
    message: String = "Forbidden"
) {
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

suspend fun ApplicationCall.respondApiError(error: ApiError) {
    respond(
        HttpStatusCode.fromValue(error.status),
        ErrorResponse(error = error.errorMessage),
    )
}

private val logger by loggerFactory()
