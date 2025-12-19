package com.alexn.socialpublish.modules

import com.alexn.socialpublish.config.AppConfig
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AuthModuleTest {
    
    private val config = AppConfig(
        dbPath = "/tmp/test.db",
        httpPort = 3000,
        baseUrl = "http://localhost:3000",
        serverAuthUsername = "testuser",
        serverAuthPassword = "testpass",
        serverAuthJwtSecret = "test-secret",
        blueskyService = "https://bsky.social",
        blueskyUsername = "",
        blueskyPassword = "",
        mastodonHost = "",
        mastodonAccessToken = "",
        twitterOauth1ConsumerKey = "",
        twitterOauth1ConsumerSecret = "",
        uploadedFilesPath = "/tmp/uploads"
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
