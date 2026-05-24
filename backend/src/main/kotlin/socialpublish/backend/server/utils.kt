package socialpublish.backend.server

import arrow.core.getOrElse
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.util.AttributeKey
import socialpublish.backend.common.ErrorResponse
import socialpublish.backend.db.UUIDv7
import socialpublish.backend.db.UserSettings
import socialpublish.backend.db.UsersDatabase
import socialpublish.backend.server.routes.resolveUserUuid

private val logger = KotlinLogging.logger {}
internal val UserUuidKey = AttributeKey<UUIDv7>("userUuid")
internal val UserSettingsKey = AttributeKey<UserSettings>("userSettings")

/**
 * Installs the UserContextPlugin that loads the authenticated user's UUID and settings into call
 * attributes once per request, avoiding repeated DB queries in downstream handlers.
 *
 * When the user does not exist in the database (e.g., was deleted after the JWT was issued), no
 * attributes are set and downstream handlers will reject the request with 401.
 */
fun Route.installUserContextPlugin(usersDb: UsersDatabase) {
    install(
        createRouteScopedPlugin("UserContextPlugin") {
            onCall { call ->
                val userUuid = call.resolveUserUuid() ?: return@onCall
                val user = usersDb.findByUuid(userUuid).getOrElse { null } ?: return@onCall
                call.attributes.put(UserUuidKey, userUuid)
                call.attributes.put(UserSettingsKey, user.settings ?: UserSettings())
            }
        }
    )
}

internal suspend fun ApplicationCall.requireUserUuid(): UUIDv7? {
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
    userUuid: UUIDv7,
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
