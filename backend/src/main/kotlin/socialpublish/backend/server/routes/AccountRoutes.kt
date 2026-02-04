package socialpublish.backend.server.routes

import arrow.core.Either
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import socialpublish.backend.common.ErrorResponse
import socialpublish.backend.db.UserSettings
import socialpublish.backend.db.UsersDatabase

@Serializable data class UserSettingsResponse(val settings: UserSettings?)

class AccountRoutes(private val usersDb: UsersDatabase) {

    /** GET /api/account/settings - Get current user's settings */
    suspend fun getSettings(call: ApplicationCall) {
        val userUuid = call.getAuthenticatedUserUuid()
        if (userUuid == null) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Unauthorized"))
            return
        }

        when (val result = usersDb.findByUuid(userUuid)) {
            is Either.Left -> {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse("Failed to load user settings"),
                )
            }
            is Either.Right -> {
                val user = result.value
                if (user == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))
                    return
                }

                val settings = user.getSettings()

                call.respond(UserSettingsResponse(settings))
            }
        }
    }

    /** PUT /api/account/settings - Update current user's settings */
    suspend fun updateSettings(call: ApplicationCall) {
        val userUuid = call.getAuthenticatedUserUuid()
        if (userUuid == null) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Unauthorized"))
            return
        }

        val newSettings =
            runCatching { call.receive<UserSettings>() }.getOrNull()
                ?: run {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("Invalid settings format"),
                    )
                    return
                }

        when (val result = usersDb.updateSettings(userUuid, newSettings)) {
            is Either.Left -> {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse("Failed to update settings"),
                )
            }
            is Either.Right -> {
                if (result.value) {
                    call.respond(UserSettingsResponse(newSettings))
                } else {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))
                }
            }
        }
    }
}
