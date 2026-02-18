package socialpublish.backend.modules

import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class AuthModuleTest {
    private val jwtSecret = "test-secret"

    @Test
    fun `should generate valid JWT token`() {
        val authModule = AuthModule(jwtSecret)
        val token = authModule.generateToken("testuser", UUID.randomUUID())

        assertNotNull(token)
        assertTrue(token.isNotEmpty())
    }

    @Test
    fun `should verify valid JWT token`() {
        val authModule = AuthModule(jwtSecret)
        val token = authModule.generateToken("testuser", UUID.randomUUID())
        val result = authModule.verifyTokenPayload(token)

        assertEquals("testuser", result?.username)
    }

    @Test
    fun `should reject invalid JWT token`() {
        val authModule = AuthModule(jwtSecret)
        val result = authModule.verifyTokenPayload("invalid-token")

        assertNull(result)
    }

    @Test
    fun `verifyToken should reject token signed with different secret`() {
        val authModule1 = AuthModule("secret1")
        val authModule2 = AuthModule("secret2")

        val token = authModule1.generateToken("testuser", UUID.randomUUID())
        val result = authModule2.verifyTokenPayload(token)

        assertNull(result)
    }

    @Test
    fun `verifyToken should reject malformed token`() {
        val authModule = AuthModule(jwtSecret)
        val result = authModule.verifyTokenPayload("not.a.valid.jwt.token")

        assertNull(result)
    }

    @Test
    fun `verifyToken should reject empty token`() {
        val authModule = AuthModule(jwtSecret)
        val result = authModule.verifyTokenPayload("")

        assertNull(result)
    }

    @Test
    fun `hashPassword should generate valid BCrypt hash`() {
        val password = "mySecurePassword123"
        val hash = AuthModule.hashPassword(password)

        assertNotNull(hash)
        assertTrue(hash.startsWith("\$2"))

        val authModule = AuthModule(jwtSecret)
        assertTrue(authModule.verifyPassword(password, hash))
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
        assertTrue(authModule.verifyPassword(password, hash1))
        assertTrue(authModule.verifyPassword(password, hash2))
    }

    @Test
    fun `verifyPassword should handle trimmed stored passwords`() {
        val authModule = AuthModule(jwtSecret)
        val password = "myPassword"
        val hash = AuthModule.hashPassword(password)

        val verified = authModule.verifyPassword(password, "  $hash  ")
        assertTrue(verified)
    }

    @Test
    fun `verifyPassword should return false for incorrect password`() {
        val authModule = AuthModule(jwtSecret)
        val hash = AuthModule.hashPassword("correctPassword")

        val verified = authModule.verifyPassword("wrongPassword", hash)
        assertEquals(false, verified)
    }

    @Test
    fun `verifyPassword should return false for malformed hash`() {
        val authModule = AuthModule(jwtSecret)

        val verified = authModule.verifyPassword("password", "not-a-valid-bcrypt-hash")
        assertEquals(false, verified)
    }

    @Test
    fun `getUserUuidFromToken should return the uuid embedded in the token`() {
        val authModule = AuthModule(jwtSecret)
        val uuid = UUID.randomUUID()
        val token = authModule.generateToken("testuser", uuid)

        val extracted = authModule.verifyTokenPayload(token)
        assertEquals(uuid, extracted?.userUuid)
    }

    @Test
    fun `getUserUuidFromToken should return null for invalid token`() {
        val authModule = AuthModule(jwtSecret)
        val result = authModule.verifyTokenPayload("invalid-token")
        assertEquals(null, result)
    }

    @Test
    fun `getUserUuidFromToken should return null for token signed with different secret`() {
        val authModule1 = AuthModule("secret1")
        val authModule2 = AuthModule("secret2")

        val token = authModule1.generateToken("testuser", UUID.randomUUID())
        val result = authModule2.verifyTokenPayload(token)
        assertEquals(null, result)
    }
}
