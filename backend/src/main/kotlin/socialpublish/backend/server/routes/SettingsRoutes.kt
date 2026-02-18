package socialpublish.backend.server.routes

import arrow.core.getOrElse
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import socialpublish.backend.common.ErrorResponse
import socialpublish.backend.db.UserSettings
import socialpublish.backend.db.UsersDatabase

private val logger = KotlinLogging.logger {}

class SettingsRoutes(private val usersDb: UsersDatabase, private val authRoutes: AuthRoutes) {
    /** GET /api/account/settings – return the authenticated user's settings */
    suspend fun getSettingsRoute(call: ApplicationCall) {
        val userUuid = authRoutes.extractUserUuidOrRespond(call) ?: return
        val user =
            usersDb.findByUuid(userUuid).getOrElse { error ->
                logger.error(error) { "Failed to retrieve user settings for $userUuid" }
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse(error = "Server error"),
                )
                return
            }
        if (user == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse(error = "User not found"))
            return
        }
        call.respond(user.settings ?: UserSettings())
    }

    /** PUT /api/account/settings – update the authenticated user's settings */
    suspend fun updateSettingsRoute(call: ApplicationCall) {
        val userUuid = authRoutes.extractUserUuidOrRespond(call) ?: return
        val newSettings =
            runCatching { call.receive<UserSettings>() }.getOrNull()
                ?: run {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(error = "Invalid settings body"),
                    )
                    return
                }
        val updated =
            usersDb.updateSettings(userUuid, newSettings).getOrElse { error ->
                logger.error(error) { "Failed to update user settings for $userUuid" }
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse(error = "Server error"),
                )
                return
            }
        if (!updated) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse(error = "User not found"))
            return
        }
        call.respond(newSettings)
    }
}
