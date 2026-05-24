package socialpublish.backend.db

import arrow.core.getOrElse
import arrow.fx.coroutines.resourceScope
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class UserSessionsDatabaseTest {
    @Test
    fun `createSession should create new session`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()

        resourceScope {
            val db = Database.connect(dbPath).bind()
            val usersDb = UsersDatabase(db)
            val userSessionsDb = UserSessionsDatabase(db, usersDb)

            val createResult =
                usersDb.createUser(username = "sessionuser", password = "password").getOrElse {
                    throw it
                }
            assertTrue(createResult is CreateResult.Created)
            val user = createResult.value

            val expiresAt = Instant.now().plusSeconds(3600)
            val sessionResult =
                userSessionsDb
                    .createSession(
                        userUuid = user.uuid,
                        tokenHash = "token-hash-123",
                        expiresAt = expiresAt,
                    )
                    .getOrElse { throw it }

            assertTrue(sessionResult is CreateResult.Created)
            val session = sessionResult.value
            assertNotNull(session.uuid)
            assertEquals(user.uuid, session.user.uuid)
            assertEquals(user.username, session.user.username)
            assertEquals("token-hash-123", session.tokenHash)
            assertEquals(expiresAt.toEpochMilli(), session.expiresAt.toEpochMilli())
            assertNotNull(session.createdAt)
            assertNull(session.revokedAt)
        }
    }

    @Test
    fun `createSession should return Duplicate for duplicate token hash`(@TempDir tempDir: Path) =
        runTest {
            val dbPath = tempDir.resolve("test.db").toString()

            resourceScope {
                val db = Database.connect(dbPath).bind()
                val usersDb = UsersDatabase(db)
                val userSessionsDb = UserSessionsDatabase(db, usersDb)

                val createResult =
                    usersDb.createUser(username = "duptoken", password = "password").getOrElse {
                        throw it
                    }
                assertTrue(createResult is CreateResult.Created)
                val user = createResult.value

                val expiresAt = Instant.now().plusSeconds(3600)
                val _ =
                    userSessionsDb
                        .createSession(
                            userUuid = user.uuid,
                            tokenHash = "duplicate-token",
                            expiresAt = expiresAt,
                        )
                        .getOrElse { throw it }

                val secondResult =
                    userSessionsDb
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
            val userSessionsDb = UserSessionsDatabase(db, usersDb)

            val createResult =
                usersDb.createUser(username = "finduser", password = "password").getOrElse {
                    throw it
                }
            assertTrue(createResult is CreateResult.Created)
            val user = createResult.value

            val sessionResult =
                userSessionsDb
                    .createSession(
                        userUuid = user.uuid,
                        tokenHash = "find-token-hash",
                        expiresAt = Instant.now().plusSeconds(3600),
                    )
                    .getOrElse { throw it }
            assertTrue(sessionResult is CreateResult.Created)
            val created = sessionResult.value

            val found =
                userSessionsDb.findSessionByTokenHash("find-token-hash").getOrElse { throw it }

            assertNotNull(found)
            assertEquals(created.uuid, found.uuid)
            assertEquals(created.user, found.user)
            assertEquals(created.tokenHash, found.tokenHash)
            assertNull(found.revokedAt)
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
            val userSessionsDb = UserSessionsDatabase(db, usersDb)

            val notFound =
                userSessionsDb.findSessionByTokenHash("nonexistent-hash").getOrElse { throw it }

            assertNull(notFound)
        }
    }

    @Test
    fun `deleteSession should delete existing session`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()

        resourceScope {
            val db = Database.connect(dbPath).bind()
            val usersDb = UsersDatabase(db)
            val userSessionsDb = UserSessionsDatabase(db, usersDb)

            val createResult =
                usersDb.createUser(username = "deleteuser", password = "password").getOrElse {
                    throw it
                }
            assertTrue(createResult is CreateResult.Created)
            val user = createResult.value
            val _ =
                userSessionsDb
                    .createSession(
                        userUuid = user.uuid,
                        tokenHash = "delete-token-hash",
                        expiresAt = Instant.now().plusSeconds(3600),
                    )
                    .getOrElse { throw it }

            val deleted = userSessionsDb.deleteSession("delete-token-hash").getOrElse { throw it }

            assertTrue(deleted)

            val notFound =
                userSessionsDb.findSessionByTokenHash("delete-token-hash").getOrElse { throw it }
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
                val userSessionsDb = UserSessionsDatabase(db, usersDb)

                val deleted =
                    userSessionsDb.deleteSession("nonexistent-hash").getOrElse { throw it }

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
                val userSessionsDb = UserSessionsDatabase(db, usersDb)

                val createResult =
                    usersDb.createUser(username = "expireuser", password = "password").getOrElse {
                        throw it
                    }
                assertTrue(createResult is CreateResult.Created)
                val user = createResult.value

                val _ =
                    userSessionsDb
                        .createSession(
                            userUuid = user.uuid,
                            tokenHash = "expired-token",
                            expiresAt = Instant.now().minusSeconds(3600),
                        )
                        .getOrElse { throw it }

                val _ =
                    userSessionsDb
                        .createSession(
                            userUuid = user.uuid,
                            tokenHash = "valid-token",
                            expiresAt = Instant.now().plusSeconds(3600),
                        )
                        .getOrElse { throw it }

                val deleted = userSessionsDb.deleteExpiredSessions().getOrElse { throw it }

                assertEquals(1, deleted)

                val expiredNotFound =
                    userSessionsDb.findSessionByTokenHash("expired-token").getOrElse { throw it }
                assertNull(expiredNotFound)

                val validFound =
                    userSessionsDb.findSessionByTokenHash("valid-token").getOrElse { throw it }
                assertNotNull(validFound)
            }
        }
}
