package socialpublish.frontend.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class LogoutTest {
    @Test
    fun `logout calls backend before clearing local session`() = runTest {
        Storage.setSessionToken("existing-token")
        Storage.setConfiguredServices(ConfiguredServices(twitter = true))
        val events = mutableListOf<String>()

        logoutAndClearLocalSession(
            logoutRequest = {
                events += "api:${Storage.getSessionToken()}"
                ApiResponse.Success(LogoutResponse(success = true))
            },
            clearSessionToken = {
                events += "clear-token:${Storage.getSessionToken()}"
                Storage.clearSessionToken()
            },
            clearConfiguredServices = {
                events += "clear-services:${Storage.getConfiguredServices().twitter}"
                Storage.setConfiguredServices(null)
            },
        )

        assertEquals(
            listOf("api:existing-token", "clear-token:existing-token", "clear-services:true"),
            events,
        )
        assertEquals(null, Storage.getSessionToken())
        assertEquals(ConfiguredServices(), Storage.getConfiguredServices())
    }

    @Test
    fun `logout clears local session when backend call throws`() = runTest {
        Storage.setSessionToken("existing-token")
        Storage.setConfiguredServices(ConfiguredServices(linkedin = true))

        logoutAndClearLocalSession(logoutRequest = { throw Throwable("Network error") })

        assertEquals(null, Storage.getSessionToken())
        assertEquals(ConfiguredServices(), Storage.getConfiguredServices())
    }
}
