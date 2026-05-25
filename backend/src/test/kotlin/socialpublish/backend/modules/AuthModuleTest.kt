package socialpublish.backend.modules

import arrow.core.Either
import arrow.core.getOrElse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import socialpublish.backend.db.Database
import socialpublish.backend.db.UserSessionsDatabase
import socialpublish.backend.db.UsersDatabase

class AuthModuleTest {
    @Test
    fun `hashPassword should generate valid BCrypt hash`() {
        val password = "mySecurePassword123"
        val hash = AuthModule.hashPassword(password)

        assertNotNull(hash)
        assertTrue(hash.startsWith("\$2"))
        assertTrue(AuthModule.verifyPassword(password, hash))
    }

    @Test
    fun `hashPassword with different rounds should produce different hashes`() {
        val password = "testPassword"
        val hash1 = AuthModule.hashPassword(password, rounds = 4)
        val hash2 = AuthModule.hashPassword(password, rounds = 8)

        assertNotNull(hash1)
        assertNotNull(hash2)
        assertTrue(hash1 != hash2)

        assertTrue(AuthModule.verifyPassword(password, hash1))
        assertTrue(AuthModule.verifyPassword(password, hash2))
    }

    @Test
    fun `verifyPassword should handle trimmed stored passwords`() {
        val password = "myPassword"
        val hash = AuthModule.hashPassword(password)

        val verified = AuthModule.verifyPassword(password, "  $hash  ")
        assertTrue(verified)
    }

    @Test
    fun `verifyPassword should return false for incorrect password`() {
        val hash = AuthModule.hashPassword("correctPassword")

        val verified = AuthModule.verifyPassword("wrongPassword", hash)
        assertFalse(verified)
    }

    @Test
    fun `verifyPassword should return false for malformed hash`() {
        val verified = AuthModule.verifyPassword("password", "not-a-valid-bcrypt-hash")
        assertFalse(verified)
    }

    @Test
    fun `verifyPassword should return false for empty password`() {
        val hash = AuthModule.hashPassword("somePassword")
        assertFalse(AuthModule.verifyPassword("", hash))
    }

    @Test
    fun `AuthService login returns CreatedUserSession for valid credentials`() = runTest {
        val db = Database.connectUnmanaged(":memory:")
        val usersDb = UsersDatabase(db)
        val userSessionsDb = UserSessionsDatabase(db, usersDb)
        val authService = AuthService(userSessionsDb)

        val _ = usersDb.createUser("testuser", "password123").getOrElse { throw it }

        val result = authService.login("testuser", "password123")
        assertTrue(result is Either.Right)
        val created = result.getOrNull()
        assertNotNull(created)
        assertTrue(created.rawToken.isNotBlank())
    }

    @Test
    fun `AuthService login returns null for invalid credentials`() = runTest {
        val db = Database.connectUnmanaged(":memory:")
        val usersDb = UsersDatabase(db)
        val userSessionsDb = UserSessionsDatabase(db, usersDb)
        val authService = AuthService(userSessionsDb)

        val _ = usersDb.createUser("testuser", "password123").getOrElse { throw it }

        val result = authService.login("testuser", "wrongpassword")
        assertTrue(result is Either.Right)
        val created = result.getOrNull()
        assertEquals(null, created)
    }

    @Test
    fun `AuthService authorize returns UserSession for valid token`() = runTest {
        val db = Database.connectUnmanaged(":memory:")
        val usersDb = UsersDatabase(db)
        val userSessionsDb = UserSessionsDatabase(db, usersDb)
        val authService = AuthService(userSessionsDb)

        val _ = usersDb.createUser("testuser", "password123").getOrElse { throw it }

        val loginResult = authService.login("testuser", "password123").getOrNull()!!
        assertNotNull(loginResult)

        val session = authService.authorize(loginResult.rawToken)
        assertTrue(session is Either.Right)
        assertNotNull(session.getOrNull())
    }

    @Test
    fun `AuthService authorize returns unauthorized for invalid token`() = runTest {
        val db = Database.connectUnmanaged(":memory:")
        val usersDb = UsersDatabase(db)
        val userSessionsDb = UserSessionsDatabase(db, usersDb)
        val authService = AuthService(userSessionsDb)

        val _ = usersDb.createUser("testuser", "password123").getOrElse { throw it }

        val result = authService.authorize("invalid-token")
        assertTrue(result is Either.Left)
        assertEquals("Unauthorized", result.value.errorMessage)
    }
}
