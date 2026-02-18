package socialpublish.frontend.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApiClientAuthTest {
    @Test
    fun `isUnauthorized returns true for 401 responses`() {
        assertTrue(isUnauthorized(ApiResponse.Error("Unauthorized", 401)))
    }

    @Test
    fun `isUnauthorized returns false for non-401 responses`() {
        assertFalse(isUnauthorized(ApiResponse.Error("Forbidden", 403)))
    }

    @Test
    fun `build login redirect path contains reason and redirect`() {
        val path = buildLoginRedirectPath("/account")
        assertEquals("/login?reason=session_expired&redirect=%2Faccount", path)
    }
}
