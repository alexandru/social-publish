package socialpublish.backend.server.routes

import arrow.core.getOrElse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import java.util.UUID
import socialpublish.backend.common.ErrorResponse
import socialpublish.backend.db.UserSettings
import socialpublish.backend.db.UsersDatabase
import socialpublish.backend.server.respondWithInternalServerError
import socialpublish.backend.server.respondWithNotFound

class SettingsRoutes(private val usersDb: UsersDatabase) {
    /** GET /api/account/settings – return the authenticated user's settings (sans credentials). */
    suspend fun getSettingsRoute(userUuid: UUID, call: ApplicationCall) {
        val user =
            usersDb.findByUuid(userUuid).getOrElse { error ->
                call.respondWithInternalServerError(error, "Failed to retrieve user $userUuid")
                return
            }
                ?: run {
                    call.respondWithNotFound("User")
                    return
                }
        // Return which services are configured, not the credentials themselves.
        call.respond(
            ConfiguredServices(
                mastodon = user.settings?.mastodon != null,
                bluesky = user.settings?.bluesky != null,
                twitter = user.settings?.twitter != null,
                linkedin = user.settings?.linkedin != null,
                llm = user.settings?.llm != null,
            )
        )
    }

    /** PUT /api/account/settings – update the authenticated user's settings. */
    suspend fun updateSettingsRoute(userUuid: UUID, call: ApplicationCall) {
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
                call.respondWithInternalServerError(
                    error,
                    "Failed to update settings for $userUuid",
                )
                return
            }
        if (!updated) {
            call.respondWithNotFound("User")
            return
        }
        // Confirm which services are now configured (no credentials echoed back)
        call.respond(
            ConfiguredServices(
                mastodon = newSettings.mastodon != null,
                bluesky = newSettings.bluesky != null,
                twitter = newSettings.twitter != null,
                linkedin = newSettings.linkedin != null,
                llm = newSettings.llm != null,
            )
        )
    }
}
