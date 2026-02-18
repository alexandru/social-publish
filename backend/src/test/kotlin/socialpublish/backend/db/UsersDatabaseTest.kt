package socialpublish.backend.db

import arrow.core.getOrElse
import arrow.fx.coroutines.resourceScope
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
                @Suppress("UNUSED_VARIABLE")
                val created =
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
    fun `verifyPassword should return true for correct password`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()

        resourceScope {
            val db = Database.connect(dbPath).bind()
            val usersDb = UsersDatabase(db)

            // Create user
            @Suppress("UNUSED_VARIABLE")
            val created =
                usersDb
                    .createUser(username = "verifyuser", password = "correctpassword")
                    .getOrElse { throw it }

            // Verify correct password
            val verified =
                usersDb.verifyPassword("verifyuser", "correctpassword").getOrElse { throw it }

            assertTrue(verified)
        }
    }

    @Test
    fun `verifyPassword should return false for incorrect password`(@TempDir tempDir: Path) =
        runTest {
            val dbPath = tempDir.resolve("test.db").toString()

            resourceScope {
                val db = Database.connect(dbPath).bind()
                val usersDb = UsersDatabase(db)

                // Create user
                @Suppress("UNUSED_VARIABLE")
                val created =
                    usersDb
                        .createUser(username = "verifyuser", password = "correctpassword")
                        .getOrElse { throw it }

                // Verify incorrect password
                val verified =
                    usersDb.verifyPassword("verifyuser", "wrongpassword").getOrElse { throw it }

                assertFalse(verified)
            }
        }

    @Test
    fun `verifyPassword should return false for non-existent user`(@TempDir tempDir: Path) =
        runTest {
            val dbPath = tempDir.resolve("test.db").toString()

            resourceScope {
                val db = Database.connect(dbPath).bind()
                val usersDb = UsersDatabase(db)

                val verified =
                    usersDb.verifyPassword("nonexistent", "anypassword").getOrElse { throw it }

                assertFalse(verified)
            }
        }

    @Test
    fun `createSession should create new session`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()

        resourceScope {
            val db = Database.connect(dbPath).bind()
            val usersDb = UsersDatabase(db)

            // Create user
            val createResult =
                usersDb.createUser(username = "sessionuser", password = "password").getOrElse {
                    throw it
                }
            assertTrue(createResult is CreateResult.Created)
            val user = createResult.value

            // Create session
            val expiresAt = Instant.now().plusSeconds(3600)
            val sessionResult =
                usersDb
                    .createSession(
                        userUuid = user.uuid,
                        tokenHash = "token-hash-123",
                        expiresAt = expiresAt,
                    )
                    .getOrElse { throw it }

            assertTrue(sessionResult is CreateResult.Created)
            val session = sessionResult.value
            assertNotNull(session.uuid)
            assertEquals(user.uuid, session.userUuid)
            assertEquals("token-hash-123", session.tokenHash)
            assertNull(session.refreshTokenHash)
            assertEquals(expiresAt.toEpochMilli(), session.expiresAt.toEpochMilli())
            assertNotNull(session.createdAt)
        }
    }

    @Test
    fun `createSession should support refresh token hash`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()

        resourceScope {
            val db = Database.connect(dbPath).bind()
            val usersDb = UsersDatabase(db)

            // Create user
            val createResult =
                usersDb.createUser(username = "refreshuser", password = "password").getOrElse {
                    throw it
                }
            assertTrue(createResult is CreateResult.Created)
            val user = createResult.value

            // Create session with refresh token
            val expiresAt = Instant.now().plusSeconds(3600)
            val sessionResult =
                usersDb
                    .createSession(
                        userUuid = user.uuid,
                        tokenHash = "token-hash-456",
                        expiresAt = expiresAt,
                        refreshTokenHash = "refresh-hash-789",
                    )
                    .getOrElse { throw it }

            assertTrue(sessionResult is CreateResult.Created)
            val session = sessionResult.value
            assertNotNull(session.refreshTokenHash)
            assertEquals("refresh-hash-789", session.refreshTokenHash)
        }
    }

    @Test
    fun `createSession should return Duplicate for duplicate token hash`(@TempDir tempDir: Path) =
        runTest {
            val dbPath = tempDir.resolve("test.db").toString()

            resourceScope {
                val db = Database.connect(dbPath).bind()
                val usersDb = UsersDatabase(db)

                // Create user
                val createResult =
                    usersDb.createUser(username = "duptoken", password = "password").getOrElse {
                        throw it
                    }
                assertTrue(createResult is CreateResult.Created)
                val user = createResult.value

                // Create first session
                val expiresAt = Instant.now().plusSeconds(3600)
                @Suppress("UNUSED_VARIABLE")
                val firstResult =
                    usersDb
                        .createSession(
                            userUuid = user.uuid,
                            tokenHash = "duplicate-token",
                            expiresAt = expiresAt,
                        )
                        .getOrElse { throw it }

                // Try to create second session with same token hash
                val secondResult =
                    usersDb
                        .createSession(
                            userUuid = user.uuid,
                            tokenHash = "duplicate-token",
                            expiresAt = expiresAt,
                        )
                        .getOrElse { throw it }

                assertTrue(secondResult is CreateResult.Duplicate)
            }
        }

    @Test
    fun `findSessionByTokenHash should find existing session`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()

        resourceScope {
            val db = Database.connect(dbPath).bind()
            val usersDb = UsersDatabase(db)

            // Create user and session
            val createResult =
                usersDb.createUser(username = "finduser", password = "password").getOrElse {
                    throw it
                }
            assertTrue(createResult is CreateResult.Created)
            val user = createResult.value

            val sessionResult =
                usersDb
                    .createSession(
                        userUuid = user.uuid,
                        tokenHash = "find-token-hash",
                        expiresAt = Instant.now().plusSeconds(3600),
                    )
                    .getOrElse { throw it }
            assertTrue(sessionResult is CreateResult.Created)
            val created = sessionResult.value

            // Find session
            val found = usersDb.findSessionByTokenHash("find-token-hash").getOrElse { throw it }

            assertNotNull(found)
            assertEquals(created.uuid, found.uuid)
            assertEquals(created.userUuid, found.userUuid)
            assertEquals(created.tokenHash, found.tokenHash)
        }
    }

    @Test
    fun `findSessionByTokenHash should return null for non-existent session`(
        @TempDir tempDir: Path
    ) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()

        resourceScope {
            val db = Database.connect(dbPath).bind()
            val usersDb = UsersDatabase(db)

            val notFound = usersDb.findSessionByTokenHash("nonexistent-hash").getOrElse { throw it }

            assertNull(notFound)
        }
    }

    @Test
    fun `deleteSession should delete existing session`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()

        resourceScope {
            val db = Database.connect(dbPath).bind()
            val usersDb = UsersDatabase(db)

            // Create user and session
            val createResult =
                usersDb.createUser(username = "deleteuser", password = "password").getOrElse {
                    throw it
                }
            assertTrue(createResult is CreateResult.Created)
            val user = createResult.value

            @Suppress("UNUSED_VARIABLE")
            val sessionResult =
                usersDb
                    .createSession(
                        userUuid = user.uuid,
                        tokenHash = "delete-token-hash",
                        expiresAt = Instant.now().plusSeconds(3600),
                    )
                    .getOrElse { throw it }

            // Delete session
            val deleted = usersDb.deleteSession("delete-token-hash").getOrElse { throw it }

            assertTrue(deleted)

            // Verify session is gone
            val notFound =
                usersDb.findSessionByTokenHash("delete-token-hash").getOrElse { throw it }
            assertNull(notFound)
        }
    }

    @Test
    fun `deleteSession should return false for non-existent session`(@TempDir tempDir: Path) =
        runTest {
            val dbPath = tempDir.resolve("test.db").toString()

            resourceScope {
                val db = Database.connect(dbPath).bind()
                val usersDb = UsersDatabase(db)

                val deleted = usersDb.deleteSession("nonexistent-hash").getOrElse { throw it }

                assertFalse(deleted)
            }
        }

    @Test
    fun `deleteExpiredSessions should delete only expired sessions`(@TempDir tempDir: Path) =
        runTest {
            val dbPath = tempDir.resolve("test.db").toString()

            resourceScope {
                val db = Database.connect(dbPath).bind()
                val usersDb = UsersDatabase(db)

                // Create user
                val createResult =
                    usersDb.createUser(username = "expireuser", password = "password").getOrElse {
                        throw it
                    }
                assertTrue(createResult is CreateResult.Created)
                val user = createResult.value

                // Create expired session (expires in the past)
                @Suppress("UNUSED_VARIABLE")
                val expiredSessionResult =
                    usersDb
                        .createSession(
                            userUuid = user.uuid,
                            tokenHash = "expired-token",
                            expiresAt = Instant.now().minusSeconds(3600),
                        )
                        .getOrElse { throw it }

                // Create valid session (expires in the future)
                @Suppress("UNUSED_VARIABLE")
                val validSessionResult =
                    usersDb
                        .createSession(
                            userUuid = user.uuid,
                            tokenHash = "valid-token",
                            expiresAt = Instant.now().plusSeconds(3600),
                        )
                        .getOrElse { throw it }

                // Delete expired sessions
                val deleted = usersDb.deleteExpiredSessions().getOrElse { throw it }

                assertEquals(1, deleted)

                // Verify expired session is gone
                val expiredNotFound =
                    usersDb.findSessionByTokenHash("expired-token").getOrElse { throw it }
                assertNull(expiredNotFound)

                // Verify valid session still exists
                val validFound =
                    usersDb.findSessionByTokenHash("valid-token").getOrElse { throw it }
                assertNotNull(validFound)
            }
        }

    @Test
    fun `CreateResult toNullable should work correctly`() {
        val user =
            User(
                uuid = UUID.randomUUID(),
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
