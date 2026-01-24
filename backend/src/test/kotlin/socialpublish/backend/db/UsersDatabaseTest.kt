package socialpublish.backend.db

import arrow.core.getOrElse
import arrow.fx.coroutines.resourceScope
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class UsersDatabaseTest {
    @Test
    fun `test create and retrieve user`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()

        resourceScope {
            val db = Database.connect(dbPath).bind()
            val usersDb = UsersDatabase(db)

            // Create a user
            val newUser = NewUser(email = "test@example.com", passwordHash = "hashed_password")
            val created = usersDb.create(newUser).getOrElse { throw it }

            // Verify created user
            assertNotNull(created.id)
            assertEquals("test@example.com", created.email)
            assertEquals("hashed_password", created.passwordHash)
            assertNotNull(created.createdAt)
            assertNotNull(created.updatedAt)

            // Find by ID
            val foundById = usersDb.findById(created.id).getOrElse { throw it }
            assertNotNull(foundById)
            assertEquals(created.id, foundById.id)
            assertEquals(created.email, foundById.email)

            // Find by email
            val foundByEmail = usersDb.findByEmail("test@example.com").getOrElse { throw it }
            assertNotNull(foundByEmail)
            assertEquals(created.id, foundByEmail.id)
            assertEquals("test@example.com", foundByEmail.email)
        }
    }

    @Test
    fun `test create user with duplicate email fails`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()

        resourceScope {
            val db = Database.connect(dbPath).bind()
            val usersDb = UsersDatabase(db)

            // Create first user
            val newUser1 = NewUser(email = "test@example.com", passwordHash = "password1")
            val user1 = usersDb.create(newUser1).getOrElse { throw it }

            // Try to create second user with same email
            val newUser2 = NewUser(email = "test@example.com", passwordHash = "password2")
            val result = usersDb.create(newUser2)

            // Should fail with unique constraint violation
            assertTrue(result.isLeft())
            assertTrue(result.leftOrNull() is SqlUpdateException.UniqueViolation)
        }
    }

    @Test
    fun `test update user`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()

        resourceScope {
            val db = Database.connect(dbPath).bind()
            val usersDb = UsersDatabase(db)

            // Create a user
            val newUser = NewUser(email = "test@example.com", passwordHash = "password1")
            val created = usersDb.create(newUser).getOrElse { throw it }

            // Update the user
            val update = UserUpdate(email = "updated@example.com", passwordHash = "password2")
            val wasUpdated = usersDb.update(created.id, update).getOrElse { throw it }
            assertTrue(wasUpdated)

            // Verify update
            val updated = usersDb.findById(created.id).getOrElse { throw it }
            assertNotNull(updated)
            assertEquals("updated@example.com", updated.email)
            assertEquals("password2", updated.passwordHash)
        }
    }

    @Test
    fun `test delete user`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()

        resourceScope {
            val db = Database.connect(dbPath).bind()
            val usersDb = UsersDatabase(db)

            // Create a user
            val newUser = NewUser(email = "test@example.com", passwordHash = "password1")
            val created = usersDb.create(newUser).getOrElse { throw it }

            // Delete the user
            val wasDeleted = usersDb.delete(created.id).getOrElse { throw it }
            assertTrue(wasDeleted)

            // Verify deletion
            val found = usersDb.findById(created.id).getOrElse { throw it }
            assertNull(found)
        }
    }

    @Test
    fun `test list all users`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()

        resourceScope {
            val db = Database.connect(dbPath).bind()
            val usersDb = UsersDatabase(db)

            // Create multiple users
            val user1 = usersDb.create(NewUser("user1@example.com", "pass1")).getOrElse { throw it }
            val user2 = usersDb.create(NewUser("user2@example.com", "pass2")).getOrElse { throw it }
            val user3 = usersDb.create(NewUser("user3@example.com", "pass3")).getOrElse { throw it }

            // List all users
            val users = usersDb.listAll().getOrElse { throw it }
            assertEquals(3, users.size)
            assertTrue(users.any { it.email == "user1@example.com" })
            assertTrue(users.any { it.email == "user2@example.com" })
            assertTrue(users.any { it.email == "user3@example.com" })
        }
    }

    @Test
    fun `test find non-existent user returns null`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()

        resourceScope {
            val db = Database.connect(dbPath).bind()
            val usersDb = UsersDatabase(db)

            val found = usersDb.findById(UUID.randomUUID()).getOrElse { throw it }
            assertNull(found)
        }
    }
}
