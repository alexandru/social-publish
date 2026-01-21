package com.alexn.socialpublish.utils

import com.alexn.socialpublish.models.AuthStatus
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
import kotlinx.browser.window

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

    // JWT Token tests
    @Test
    fun testSetAndGetJwtToken() {
        Storage.setJwtToken("jwt-token-123")

        assertNotNull(Storage.getJwtToken())
        assertEquals("jwt-token-123", Storage.getJwtToken())
    }

    @Test
    fun testHasJwtToken() {
        assertFalse(Storage.hasJwtToken())

        Storage.setJwtToken("some-token")
        assertTrue(Storage.hasJwtToken())
    }

    @Test
    fun testClearJwtToken() {
        Storage.setJwtToken("token-to-clear")
        assertTrue(Storage.hasJwtToken())

        Storage.clearJwtToken()
        assertFalse(Storage.hasJwtToken())
        assertNull(Storage.getJwtToken())
    }

    @Test
    fun testGetJwtTokenWhenNotSet() {
        assertNull(Storage.getJwtToken())
    }

    @Test
    fun testJwtTokenPersistence() {
        val token = "persistent-token-456"
        Storage.setJwtToken(token)

        // Verify token is stored in cookies
        val cookies = Storage.cookies()
        assertTrue(cookies.containsKey("access_token"))
        assertEquals(token, cookies["access_token"])
    }

    @Test
    fun testJwtTokenSameSiteAttribute() {
        Storage.setJwtToken("token-with-samesite")

        // Token should be set
        assertTrue(Storage.hasJwtToken())
        // The actual SameSite attribute is set to "Lax" in the implementation
    }

    @Test
    fun testJwtTokenSecureFlagBasedOnProtocol() {
        Storage.setJwtToken("secure-test-token")

        // Token should be set regardless of protocol
        assertTrue(Storage.hasJwtToken())

        // Secure flag should be true only if protocol is https
        val isHttps = window.location.protocol == "https:"
        // We can verify the token exists but can't directly verify the secure flag from JS
        assertNotNull(Storage.getJwtToken())
    }

    // localStorage / AuthStatus tests
    @Test
    fun testSetAndGetAuthStatus() {
        val authStatus = AuthStatus(twitter = true)
        Storage.setAuthStatus(authStatus)

        val retrieved = Storage.getAuthStatus()
        assertEquals(authStatus, retrieved)
        assertTrue(retrieved.twitter)
    }

    @Test
    fun testGetAuthStatusReturnsDefaultWhenNotSet() {
        val retrieved = Storage.getAuthStatus()

        assertEquals(AuthStatus(), retrieved)
        assertFalse(retrieved.twitter)
    }

    @Test
    fun testSetAuthStatusNull() {
        // First set a value
        Storage.setAuthStatus(AuthStatus(twitter = true))
        assertTrue(Storage.getAuthStatus().twitter)

        // Then clear it
        Storage.setAuthStatus(null)
        val retrieved = Storage.getAuthStatus()

        assertEquals(AuthStatus(), retrieved)
        assertFalse(retrieved.twitter)
    }

    @Test
    fun testUpdateAuthStatus() {
        // Set initial status
        Storage.setAuthStatus(AuthStatus(twitter = false))

        // Update it
        Storage.updateAuthStatus { AuthStatus(twitter = true) }

        val retrieved = Storage.getAuthStatus()
        assertTrue(retrieved.twitter)
    }

    @Test
    fun testUpdateAuthStatusWithTransformation() {
        Storage.setAuthStatus(AuthStatus(twitter = false))

        Storage.updateAuthStatus { current -> current.copy(twitter = !current.twitter) }

        assertTrue(Storage.getAuthStatus().twitter)
    }

    @Test
    fun testAuthStatusPersistenceInLocalStorage() {
        val authStatus = AuthStatus(twitter = true)
        Storage.setAuthStatus(authStatus)

        // Verify it's stored in localStorage
        val stored = localStorage.getItem("hasAuth")
        assertNotNull(stored)
        assertTrue(stored.contains("twitter"))
    }

    @Test
    fun testGetAuthStatusHandlesCorruptedData() {
        // Manually set invalid JSON in localStorage
        localStorage.setItem("hasAuth", "invalid-json-{{{")

        // Should return default AuthStatus without throwing
        val retrieved = Storage.getAuthStatus()
        assertEquals(AuthStatus(), retrieved)
        assertFalse(retrieved.twitter)
    }

    @Test
    fun testSetAuthStatusNullRemovesFromLocalStorage() {
        Storage.setAuthStatus(AuthStatus(twitter = true))
        assertNotNull(localStorage.getItem("hasAuth"))

        Storage.setAuthStatus(null)
        assertNull(localStorage.getItem("hasAuth"))
    }

    // Integration tests
    @Test
    fun testCompleteAuthenticationFlow() {
        // Initial state: no token, no auth status
        assertFalse(Storage.hasJwtToken())
        assertEquals(AuthStatus(), Storage.getAuthStatus())

        // Login: set token and auth status
        Storage.setJwtToken("user-session-token")
        Storage.setAuthStatus(AuthStatus(twitter = true))

        assertTrue(Storage.hasJwtToken())
        assertTrue(Storage.getAuthStatus().twitter)

        // Logout: clear everything
        Storage.clearJwtToken()
        Storage.setAuthStatus(null)

        assertFalse(Storage.hasJwtToken())
        assertEquals(AuthStatus(), Storage.getAuthStatus())
    }

    @Test
    fun testStorageIsolation() {
        // JWT tokens use cookies
        Storage.setJwtToken("cookie-token")

        // Auth status uses localStorage
        Storage.setAuthStatus(AuthStatus(twitter = true))

        // Both should be independent
        assertTrue(Storage.hasJwtToken())
        assertTrue(Storage.getAuthStatus().twitter)

        // Clearing one shouldn't affect the other
        Storage.clearJwtToken()
        assertTrue(Storage.getAuthStatus().twitter)

        Storage.setAuthStatus(null)
        assertFalse(Storage.hasJwtToken())
    }
}
