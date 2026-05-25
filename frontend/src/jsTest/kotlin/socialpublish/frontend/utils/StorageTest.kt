package socialpublish.frontend.utils

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.browser.document
import kotlinx.browser.localStorage

class StorageTest {

    @BeforeTest
    fun setup() {
        // Clear all cookies and localStorage before each test
        clearAllCookies()
        localStorage.clear()
    }

    @AfterTest
    fun teardown() {
        // Clean up after each test
        clearAllCookies()
        localStorage.clear()
    }

    private fun clearAllCookies() {
        val cookies = document.cookie.split(";")
        for (cookie in cookies) {
            val name = cookie.split("=")[0].trim()
            val expiryDate = kotlin.js.Date(0)
            document.cookie = "$name=;expires=${expiryDate.toUTCString()};path=/"
        }
    }

    // Cookie tests
    @Test
    fun testSetAndGetCookie() {
        Storage.setCookie("test_cookie", "test_value")
        val cookies = Storage.cookies()

        assertTrue(cookies.containsKey("test_cookie"))
        assertEquals("test_value", cookies["test_cookie"])
    }

    @Test
    fun testSetCookieWithExpiration() {
        // Set cookie with 1 hour expiration
        Storage.setCookie("expiring_cookie", "value", expirationMillis = 3600000)
        val cookies = Storage.cookies()

        assertTrue(cookies.containsKey("expiring_cookie"))
        assertEquals("value", cookies["expiring_cookie"])
    }

    @Test
    fun testSetCookieWithSecureFlag() {
        // Note: Secure flag behavior depends on the protocol (http vs https)
        Storage.setCookie("secure_cookie", "secure_value", secure = true)
        val cookies = Storage.cookies()

        // Cookie should still be set (browser may ignore secure flag on http)
        assertTrue(cookies.containsKey("secure_cookie"))
    }

    @Test
    fun testSetCookieWithSameSite() {
        Storage.setCookie("samesite_cookie", "value", sameSite = "Strict")
        val cookies = Storage.cookies()

        assertTrue(cookies.containsKey("samesite_cookie"))
        assertEquals("value", cookies["samesite_cookie"])
    }

    @Test
    fun testClearCookie() {
        Storage.setCookie("temp_cookie", "temp_value")
        assertTrue(Storage.cookies().containsKey("temp_cookie"))

        Storage.clearCookie("temp_cookie")
        assertFalse(Storage.cookies().containsKey("temp_cookie"))
    }

    @Test
    fun testCookiesReturnsEmptyMapWhenNoCookies() {
        clearAllCookies()
        val cookies = Storage.cookies()

        assertTrue(cookies.isEmpty())
    }

    @Test
    fun testCookiesWithMultipleCookies() {
        Storage.setCookie("cookie1", "value1")
        Storage.setCookie("cookie2", "value2")
        Storage.setCookie("cookie3", "value3")

        val cookies = Storage.cookies()

        assertEquals(3, cookies.size)
        assertEquals("value1", cookies["cookie1"])
        assertEquals("value2", cookies["cookie2"])
        assertEquals("value3", cookies["cookie3"])
    }

    // Session token tests
    @Test
    fun testSetAndGetSessionToken() {
        Storage.setSessionToken("session-token-123")

        assertNotNull(Storage.getSessionToken())
        assertEquals("session-token-123", Storage.getSessionToken())
    }

    @Test
    fun testHasSessionToken() {
        assertFalse(Storage.hasSessionToken())

        Storage.setSessionToken("some-token")
        assertTrue(Storage.hasSessionToken())
    }

    @Test
    fun testClearSessionToken() {
        Storage.setSessionToken("token-to-clear")
        assertTrue(Storage.hasSessionToken())

        Storage.clearSessionToken()
        assertFalse(Storage.hasSessionToken())
        assertNull(Storage.getSessionToken())
    }

    @Test
    fun testGetSessionTokenWhenNotSet() {
        assertNull(Storage.getSessionToken())
    }

    @Test
    fun testSessionTokenPersistence() {
        val token = "persistent-token-456"
        Storage.setSessionToken(token)

        // Verify token is stored in cookies
        val cookies = Storage.cookies()
        assertTrue(cookies.containsKey("access_token"))
        assertEquals(token, cookies["access_token"])
    }

    @Test
    fun testSessionTokenSameSiteAttribute() {
        Storage.setSessionToken("token-with-samesite")

        // Token should be set
        assertTrue(Storage.hasSessionToken())
        // The actual SameSite attribute is set to "Lax" in the implementation
    }

    @Test
    fun testSessionTokenSecureFlagBasedOnProtocol() {
        Storage.setSessionToken("secure-test-token")

        // Token should be set regardless of protocol
        assertTrue(Storage.hasSessionToken())

        // We can verify the token exists but can't directly verify the secure flag from JS
        assertNotNull(Storage.getSessionToken())
    }

    // localStorage / ConfiguredServices tests
    @Test
    fun testSetAndGetConfiguredServices() {
        val services = ConfiguredServices(twitter = true)
        Storage.setConfiguredServices(services)

        val retrieved = Storage.getConfiguredServices()
        assertEquals(services, retrieved)
        assertTrue(retrieved.twitter)
    }

    @Test
    fun testGetConfiguredServicesReturnsDefaultWhenNotSet() {
        val retrieved = Storage.getConfiguredServices()

        assertEquals(ConfiguredServices(), retrieved)
        assertFalse(retrieved.twitter)
    }

    @Test
    fun testSetConfiguredServicesNull() {
        // First set a value
        Storage.setConfiguredServices(ConfiguredServices(twitter = true))
        assertTrue(Storage.getConfiguredServices().twitter)

        // Then clear it
        Storage.setConfiguredServices(null)
        val retrieved = Storage.getConfiguredServices()

        assertEquals(ConfiguredServices(), retrieved)
        assertFalse(retrieved.twitter)
    }

    @Test
    fun testUpdateConfiguredServices() {
        // Set initial status
        Storage.setConfiguredServices(ConfiguredServices(twitter = false))

        // Update it
        Storage.setConfiguredServices(ConfiguredServices(twitter = true))

        val retrieved = Storage.getConfiguredServices()
        assertTrue(retrieved.twitter)
    }

    @Test
    fun testUpdateConfiguredServicesWithTransformation() {
        val initial = ConfiguredServices(twitter = false)
        Storage.setConfiguredServices(initial)

        val updated = initial.copy(twitter = !initial.twitter)
        Storage.setConfiguredServices(updated)

        assertTrue(Storage.getConfiguredServices().twitter)
    }

    @Test
    fun testConfiguredServicesPersistenceInLocalStorage() {
        val services = ConfiguredServices(twitter = true)
        Storage.setConfiguredServices(services)

        // Verify it's stored in localStorage
        val stored = localStorage.getItem("configuredServices")
        assertNotNull(stored)
        assertTrue(stored.contains("twitter"))
    }

    @Test
    fun testGetConfiguredServicesHandlesCorruptedData() {
        // Manually set invalid JSON in localStorage
        localStorage.setItem("configuredServices", "invalid-json-{{{")

        // Should return default ConfiguredServices without throwing
        val retrieved = Storage.getConfiguredServices()
        assertEquals(ConfiguredServices(), retrieved)
        assertFalse(retrieved.twitter)
    }

    @Test
    fun testSetConfiguredServicesNullRemovesFromLocalStorage() {
        Storage.setConfiguredServices(ConfiguredServices(twitter = true))
        assertNotNull(localStorage.getItem("configuredServices"))

        Storage.setConfiguredServices(null)
        assertNull(localStorage.getItem("configuredServices"))
    }

    // Integration tests
    @Test
    fun testCompleteAuthenticationFlow() {
        // Initial state: no token, no configured services
        assertFalse(Storage.hasSessionToken())
        assertEquals(ConfiguredServices(), Storage.getConfiguredServices())

        // Login: set token and configured services
        Storage.setSessionToken("user-session-token")
        Storage.setConfiguredServices(ConfiguredServices(twitter = true))

        assertTrue(Storage.hasSessionToken())
        assertTrue(Storage.getConfiguredServices().twitter)

        // Logout: clear everything
        Storage.clearSessionToken()
        Storage.setConfiguredServices(null)

        assertFalse(Storage.hasSessionToken())
        assertEquals(ConfiguredServices(), Storage.getConfiguredServices())
    }

    @Test
    fun testStorageIsolation() {
        // Session tokens use cookies
        Storage.setSessionToken("cookie-token")

        // Configured services use localStorage
        Storage.setConfiguredServices(ConfiguredServices(twitter = true))

        // Both should be independent
        assertTrue(Storage.hasSessionToken())
        assertTrue(Storage.getConfiguredServices().twitter)

        // Clearing one shouldn't affect the other
        Storage.clearSessionToken()
        assertTrue(Storage.getConfiguredServices().twitter)

        Storage.setConfiguredServices(null)
        assertFalse(Storage.hasSessionToken())
    }
}
