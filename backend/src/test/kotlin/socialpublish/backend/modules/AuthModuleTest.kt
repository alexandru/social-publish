package socialpublish.backend.modules

import arrow.core.getOrElse
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class AuthModuleTest {
    private val jwtSecret = "test-secret"

    @Test
    fun `should generate valid JWT token`() {
        val authModule = AuthModule(jwtSecret)
        val token = authModule.generateToken("testuser")

        assertNotNull(token)
        assertTrue(token.isNotEmpty())
    }

    @Test
    fun `should verify valid JWT token`() {
        val authModule = AuthModule(jwtSecret)
        val token = authModule.generateToken("testuser")
        val username = authModule.verifyToken(token).getOrElse { null }

        assertEquals("testuser", username)
    }

    @Test
    fun `should reject invalid JWT token`() {
        val authModule = AuthModule(jwtSecret)
        val username = authModule.verifyToken("invalid-token").getOrElse { null }

        assertEquals(null, username)
    }

    @Test
    fun `verifyToken should reject token signed with different secret`() {
        val authModule1 = AuthModule("secret1")
        val authModule2 = AuthModule("secret2")

        val token = authModule1.generateToken("testuser")
        val username = authModule2.verifyToken(token).getOrElse { null }

        assertEquals(null, username)
    }

    @Test
    fun `verifyToken should reject malformed token`() {
        val authModule = AuthModule(jwtSecret)
        val username = authModule.verifyToken("not.a.valid.jwt.token").getOrElse { null }

        assertEquals(null, username)
    }

    @Test
    fun `verifyToken should reject empty token`() {
        val authModule = AuthModule(jwtSecret)
        val username = authModule.verifyToken("").getOrElse { null }

        assertEquals(null, username)
    }

    @Test
    fun `hashPassword should generate valid BCrypt hash`() {
        val password = "mySecurePassword123"
        val hash = AuthModule.hashPassword(password)

        assertNotNull(hash)
        assertTrue(hash.startsWith("\$2"))

        val authModule = AuthModule(jwtSecret)
        val verified = authModule.verifyPassword(password, hash).getOrElse { false }
        assertEquals(true, verified)
    }

    @Test
    fun `hashPassword with different rounds should produce different hashes`() {
        val password = "testPassword"
        val hash1 = AuthModule.hashPassword(password, rounds = 4)
        val hash2 = AuthModule.hashPassword(password, rounds = 8)

        assertNotNull(hash1)
        assertNotNull(hash2)
        assertTrue(hash1 != hash2)

        val authModule = AuthModule(jwtSecret)
        assertTrue(authModule.verifyPassword(password, hash1).getOrElse { false })
        assertTrue(authModule.verifyPassword(password, hash2).getOrElse { false })
    }

    @Test
    fun `verifyPassword should handle trimmed stored passwords`() {
        val authModule = AuthModule(jwtSecret)
        val password = "myPassword"
        val hash = AuthModule.hashPassword(password)

        val verified = authModule.verifyPassword(password, "  $hash  ").getOrElse { false }
        assertTrue(verified)
    }

    @Test
    fun `verifyPassword should return false for incorrect password`() {
        val authModule = AuthModule(jwtSecret)
        val hash = AuthModule.hashPassword("correctPassword")

        val verified = authModule.verifyPassword("wrongPassword", hash).getOrElse { false }
        assertEquals(false, verified)
    }

    @Test
    fun `verifyPassword should return false for malformed hash`() {
        val authModule = AuthModule(jwtSecret)

        val verified =
            authModule.verifyPassword("password", "not-a-valid-bcrypt-hash").getOrElse { false }
        assertEquals(false, verified)
    }

    @Test
    fun `verifyPassword should return false for empty hash`() {
        val authModule = AuthModule(jwtSecret)

        val result = authModule.verifyPassword("password", "")
        // Empty hash should result in an error (Left), not a false verification (Right(false))
        assertTrue(result.isLeft())
    }
}
