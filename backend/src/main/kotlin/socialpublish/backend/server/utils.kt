package socialpublish.backend.server

import arrow.core.getOrElse
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.util.AttributeKey
import java.util.UUID
import socialpublish.backend.common.ErrorResponse
import socialpublish.backend.db.UserSettings
import socialpublish.backend.db.UsersDatabase
import socialpublish.backend.server.routes.resolveUserUuid

private val logger = KotlinLogging.logger {}
internal val UserUuidKey = AttributeKey<UUID>("userUuid")
internal val UserSettingsKey = AttributeKey<UserSettings>("userSettings")

internal suspend fun ApplicationCall.requireUserUuid(): UUID? {
    attributes.getOrNull(UserUuidKey)?.let {
        return it
    }

    val resolved = resolveUserUuid()
    if (resolved == null) {
        respondWithUnauthorized()
        return null
    }

    attributes.put(UserUuidKey, resolved)
    return resolved
}

internal suspend fun ApplicationCall.requireUserSettings(
    usersDb: UsersDatabase,
    userUuid: UUID,
): UserSettings {
    attributes.getOrNull(UserSettingsKey)?.let {
        return it
    }

    val settings = usersDb.findByUuid(userUuid).getOrElse { null }?.settings ?: UserSettings()
    attributes.put(UserSettingsKey, settings)
    return settings
}

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
