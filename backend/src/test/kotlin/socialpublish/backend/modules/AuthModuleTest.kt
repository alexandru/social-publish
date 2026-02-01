package socialpublish.backend.modules

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
        val result = authModule.verifyToken(token)

        assertTrue(result.isRight())
        when (result) {
            is arrow.core.Either.Right -> assertEquals("testuser", result.value)
            is arrow.core.Either.Left -> throw AssertionError("Expected Right, got Left")
        }
    }

    @Test
    fun `should reject invalid JWT token`() {
        val authModule = AuthModule(jwtSecret)
        val result = authModule.verifyToken("invalid-token")

        assertTrue(result.isLeft())
    }

    @Test
    fun `verifyToken should reject token signed with different secret`() {
        val authModule1 = AuthModule("secret1")
        val authModule2 = AuthModule("secret2")

        val token = authModule1.generateToken("testuser")
        val result = authModule2.verifyToken(token)

        assertTrue(result.isLeft())
    }

    @Test
    fun `verifyToken should reject malformed token`() {
        val authModule = AuthModule(jwtSecret)
        val result = authModule.verifyToken("not.a.valid.jwt.token")

        assertTrue(result.isLeft())
    }

    @Test
    fun `verifyToken should reject empty token`() {
        val authModule = AuthModule(jwtSecret)
        val result = authModule.verifyToken("")

        assertTrue(result.isLeft())
    }

    @Test
    fun `hashPassword should generate valid BCrypt hash`() {
        val password = "mySecurePassword123"
        val hash = AuthModule.hashPassword(password)

        assertNotNull(hash)
        assertTrue(hash.startsWith("\$2"))

        val authModule = AuthModule(jwtSecret)
        assertTrue(authModule.verifyPassword(password, hash).isRight())
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
        assertTrue(authModule.verifyPassword(password, hash1).isRight())
        assertTrue(authModule.verifyPassword(password, hash2).isRight())
    }

    @Test
    fun `verifyPassword should handle trimmed stored passwords`() {
        val authModule = AuthModule(jwtSecret)
        val password = "myPassword"
        val hash = AuthModule.hashPassword(password)

        assertTrue(authModule.verifyPassword(password, "  $hash  ").isRight())
    }

    @Test
    fun `verifyPassword should return error for incorrect password`() {
        val authModule = AuthModule(jwtSecret)
        val hash = AuthModule.hashPassword("correctPassword")

        assertTrue(authModule.verifyPassword("wrongPassword", hash).isLeft())
    }

    @Test
    fun `verifyPassword should return error for malformed hash`() {
        val authModule = AuthModule(jwtSecret)

        assertTrue(authModule.verifyPassword("password", "not-a-valid-bcrypt-hash").isLeft())
    }

    @Test
    fun `verifyPassword should return error for empty hash`() {
        val authModule = AuthModule(jwtSecret)

        val result = authModule.verifyPassword("password", "")
        assertTrue(result.isLeft())
    }
}
