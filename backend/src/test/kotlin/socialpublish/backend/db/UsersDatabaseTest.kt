package socialpublish.backend.db

import arrow.core.getOrElse
import arrow.fx.coroutines.resourceScope
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class UsersDatabaseTest {
    @Test
    fun `createUser should create new user with hashed password`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()

        resourceScope {
            val db = Database.connect(dbPath).bind()
            val usersDb = UsersDatabase(db)

            val result =
                usersDb.createUser(username = "testuser", password = "testpassword").getOrElse {
                    throw it
                }

            assertTrue(result is CreateResult.Created)
            val user = result.value
            assertNotNull(user.uuid)
            assertEquals("testuser", user.username)
            val passwordHash = user.passwordHash
            assertNotNull(passwordHash)
            // BCrypt hashes start with $2a$, $2b$, or $2y$
            assertTrue(
                passwordHash.startsWith("\$2a\$") ||
                    passwordHash.startsWith("\$2b\$") ||
                    passwordHash.startsWith("\$2y\$")
            )
            assertNotNull(user.createdAt)
            assertNotNull(user.updatedAt)
            assertEquals(user.createdAt, user.updatedAt)
        }
    }

    @Test
    fun `createUser should return Duplicate for duplicate username`(@TempDir tempDir: Path) =
        runTest {
            val dbPath = tempDir.resolve("test.db").toString()

            resourceScope {
                val db = Database.connect(dbPath).bind()
                val usersDb = UsersDatabase(db)

                // Create first user
                val _ =
                    usersDb.createUser(username = "duplicate", password = "password1").getOrElse {
                        throw it
                    }
                // Try to create second user with same username
                val result =
                    usersDb.createUser(username = "duplicate", password = "password2").getOrElse {
                        throw it
                    }
                assertTrue(result is CreateResult.Duplicate)
            }
        }

    @Test
    fun `findByUsername should find existing user`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()

        resourceScope {
            val db = Database.connect(dbPath).bind()
            val usersDb = UsersDatabase(db)

            // Create user
            val createResult =
                usersDb.createUser(username = "findme", password = "findpassword").getOrElse {
                    throw it
                }
            assertTrue(createResult is CreateResult.Created)
            val created = createResult.value

            // Find user
            val found = usersDb.findByUsername("findme").getOrElse { throw it }

            assertNotNull(found)
            assertEquals(created.uuid, found.uuid)
            assertEquals(created.username, found.username)
            assertEquals(created.passwordHash, found.passwordHash)
            assertEquals(created.createdAt.toEpochMilli(), found.createdAt.toEpochMilli())
        }
    }

    @Test
    fun `findByUsername should return null for non-existent user`(@TempDir tempDir: Path) =
        runTest {
            val dbPath = tempDir.resolve("test.db").toString()

            resourceScope {
                val db = Database.connect(dbPath).bind()
                val usersDb = UsersDatabase(db)

                val notFound = usersDb.findByUsername("nonexistent").getOrElse { throw it }

                assertNull(notFound)
            }
        }

    @Test
    fun `verifyPassword should return User for correct password`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()

        resourceScope {
            val db = Database.connect(dbPath).bind()
            val usersDb = UsersDatabase(db)

            // Create user
            val createResult =
                usersDb
                    .createUser(username = "verifyuser", password = "correctpassword")
                    .getOrElse { throw it }
            assertTrue(createResult is CreateResult.Created)
            val createdUser = createResult.toNullable!!

            // Verify correct password
            val verified =
                usersDb.verifyPassword("verifyuser", "correctpassword").getOrElse { throw it }

            assertNotNull(verified)
            assertEquals(createdUser.uuid, verified.uuid)
            assertEquals(createdUser.username, verified.username)
        }
    }

    @Test
    fun `verifyPassword should return null for incorrect password`(@TempDir tempDir: Path) =
        runTest {
            val dbPath = tempDir.resolve("test.db").toString()

            resourceScope {
                val db = Database.connect(dbPath).bind()
                val usersDb = UsersDatabase(db)

                // Create user
                val _ =
                    usersDb
                        .createUser(username = "verifyuser", password = "correctpassword")
                        .getOrElse { throw it }

                // Verify incorrect password
                val verified =
                    usersDb.verifyPassword("verifyuser", "wrongpassword").getOrElse { throw it }

                assertNull(verified)
            }
        }

    @Test
    fun `verifyPassword should return null for non-existent user`(@TempDir tempDir: Path) =
        runTest {
            val dbPath = tempDir.resolve("test.db").toString()

            resourceScope {
                val db = Database.connect(dbPath).bind()
                val usersDb = UsersDatabase(db)

                val verified =
                    usersDb.verifyPassword("nonexistent", "anypassword").getOrElse { throw it }

                assertNull(verified)
            }
        }

    @Test
    fun `updateUsername should update username successfully`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()

        resourceScope {
            val db = Database.connect(dbPath).bind()
            val usersDb = UsersDatabase(db)

            // Create user
            val createResult =
                usersDb.createUser(username = "oldusername", password = "password").getOrElse {
                    throw it
                }
            assertTrue(createResult is CreateResult.Created)

            // Update username
            val updateResult =
                usersDb.updateUsername("oldusername", "newusername").getOrElse { throw it }

            assertTrue(updateResult is UpdateUsernameResult.Success)

            // Verify old username no longer exists
            val oldNotFound = usersDb.findByUsername("oldusername").getOrElse { throw it }
            assertNull(oldNotFound)

            // Verify new username exists
            val found = usersDb.findByUsername("newusername").getOrElse { throw it }
            assertNotNull(found)
            assertEquals("newusername", found.username)
        }
    }

    @Test
    fun `updateUsername should return UserNotFound for non-existent user`(@TempDir tempDir: Path) =
        runTest {
            val dbPath = tempDir.resolve("test.db").toString()

            resourceScope {
                val db = Database.connect(dbPath).bind()
                val usersDb = UsersDatabase(db)

                val result =
                    usersDb.updateUsername("nonexistent", "newusername").getOrElse { throw it }

                assertTrue(result is UpdateUsernameResult.UserNotFound)
            }
        }

    @Test
    fun `updateUsername should return UsernameAlreadyExists when new username exists`(
        @TempDir tempDir: Path
    ) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()

        resourceScope {
            val db = Database.connect(dbPath).bind()
            val usersDb = UsersDatabase(db)

            // Create two users
            val _ =
                usersDb.createUser(username = "user1", password = "password1").getOrElse {
                    throw it
                }
            val _ =
                usersDb.createUser(username = "user2", password = "password2").getOrElse {
                    throw it
                }

            // Try to change user1's username to user2's username
            val result = usersDb.updateUsername("user1", "user2").getOrElse { throw it }

            assertTrue(result is UpdateUsernameResult.UsernameAlreadyExists)

            // Verify both users still have their original usernames
            val found1 = usersDb.findByUsername("user1").getOrElse { throw it }
            assertNotNull(found1)
            assertEquals("user1", found1.username)

            val found2 = usersDb.findByUsername("user2").getOrElse { throw it }
            assertNotNull(found2)
            assertEquals("user2", found2.username)
        }
    }

    @Test
    fun `updateUsername should succeed when current and new usernames are the same`(
        @TempDir tempDir: Path
    ) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()

        resourceScope {
            val db = Database.connect(dbPath).bind()
            val usersDb = UsersDatabase(db)

            val createResult =
                usersDb.createUser(username = "sameuser", password = "password").getOrElse {
                    throw it
                }
            assertTrue(createResult is CreateResult.Created)
            val originalUser = createResult.value

            val updateResult = usersDb.updateUsername("sameuser", "sameuser").getOrElse { throw it }

            assertTrue(updateResult is UpdateUsernameResult.Success)

            val found = usersDb.findByUsername("sameuser").getOrElse { throw it }
            assertNotNull(found)
            assertEquals(originalUser.uuid, found.uuid)
            assertEquals("sameuser", found.username)
        }
    }

    @Test
    fun `updatePassword should invalidate sessions only for changed user`(@TempDir tempDir: Path) =
        runTest {
            val dbPath = tempDir.resolve("test.db").toString()

            resourceScope {
                val db = Database.connect(dbPath).bind()
                val usersDb = UsersDatabase(db)
                val userSessionsDb = UserSessionsDatabase(db, usersDb)

                val changedUser =
                    usersDb.createUser("changed", "old-password").getOrElse { throw it }
                assertTrue(changedUser is CreateResult.Created)
                val otherUser = usersDb.createUser("other", "password").getOrElse { throw it }
                assertTrue(otherUser is CreateResult.Created)

                val changedSession1 =
                    userSessionsDb.login("changed", "old-password").getOrElse { throw it }
                val changedSession2 =
                    userSessionsDb.login("changed", "old-password").getOrElse { throw it }
                val otherSession = userSessionsDb.login("other", "password").getOrElse { throw it }

                assertNotNull(changedSession1)
                assertNotNull(changedSession2)
                assertNotNull(otherSession)

                val updated =
                    usersDb.updatePassword("changed", "new-password").getOrElse { throw it }

                assertTrue(updated)
                assertNull(
                    userSessionsDb.authorize(changedSession1.rawToken).getOrElse { throw it }
                )
                assertNull(
                    userSessionsDb.authorize(changedSession2.rawToken).getOrElse { throw it }
                )
                assertNotNull(
                    userSessionsDb.authorize(otherSession.rawToken).getOrElse { throw it }
                )
                assertNull(usersDb.verifyPassword("changed", "old-password").getOrElse { throw it })
                assertNotNull(
                    usersDb.verifyPassword("changed", "new-password").getOrElse { throw it }
                )
            }
        }

    @Test
    fun `CreateResult toNullable should work correctly`() {
        val user =
            User(
                uuid = UUIDv7.generate(),
                username = "test",
                passwordHash = "hash",
                settings = null,
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            )
        val created: CreateResult<User> = CreateResult.Created(user)
        val duplicate: CreateResult<User> = CreateResult.Duplicate

        assertEquals(user, created.toNullable)
        assertNull(duplicate.toNullable)
    }
}
