package socialpublish.frontend.utils

import kotlinx.serialization.Serializable

@Serializable data class LogoutResponse(val success: Boolean)

suspend fun logoutAndClearLocalSession(
    logoutRequest: suspend () -> ApiResponse<LogoutResponse> = {
        ApiClient.post<LogoutResponse, Unit>("/api/logout")
    },
    clearSessionToken: () -> Unit = Storage::clearSessionToken,
    clearConfiguredServices: () -> Unit = {
        Storage.setConfiguredServices(null)
    },
) {
    try {
        val _ = logoutRequest()
    } catch (e: Throwable) {
        console.warn("Logout API request failed:", e)
    } finally {
        clearSessionToken()
        clearConfiguredServices()
    }
}
