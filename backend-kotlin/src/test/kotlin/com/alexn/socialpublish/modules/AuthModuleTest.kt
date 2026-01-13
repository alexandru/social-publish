package com.alexn.socialpublish.modules

import com.alexn.socialpublish.server.ServerAuthConfig
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AuthModuleTest {
    private val config =
        ServerAuthConfig(
            username = "testuser",
            password = "testpass",
            jwtSecret = "test-secret",
        )

    @Test
    fun `should generate valid JWT token`() {
        val authModule = AuthModule(config)
        val token = authModule.generateToken("testuser")

        assertNotNull(token)
        assertTrue(token.isNotEmpty())
    }

    @Test
    fun `should verify valid JWT token`() {
        val authModule = AuthModule(config)
        val token = authModule.generateToken("testuser")
        val username = authModule.verifyToken(token)

        assertEquals("testuser", username)
    }

    @Test
    fun `should reject invalid JWT token`() {
        val authModule = AuthModule(config)
        val username = authModule.verifyToken("invalid-token")

        assertEquals(null, username)
    }
}
