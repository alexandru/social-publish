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
            val user = createResult.toNullable!!

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
            val session = sessionResult.toNullable!!
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
                val user = createResult.toNullable!!

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
            val user = createResult.toNullable!!

            val sessionResult =
                userSessionsDb
                    .createSession(
                        userUuid = user.uuid,
                        tokenHash = "find-token-hash",
                        expiresAt = Instant.now().plusSeconds(3600),
                    )
                    .getOrElse { throw it }
            assertTrue(sessionResult is CreateResult.Created)
            val created = sessionResult.toNullable!!

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
            val user = createResult.toNullable!!
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
                val user = createResult.toNullable!!

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

    // ---- New tests for login, authorize, idempotent logout, multiple sessions ----

    @Test
    fun `login returns CreatedUserSession for valid credentials`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()

        resourceScope {
            val db = Database.connect(dbPath).bind()
            val usersDb = UsersDatabase(db)
            val userSessionsDb = UserSessionsDatabase(db, usersDb)

            val _ =
                usersDb.createUser(username = "logintest", password = "password").getOrElse {
                    throw it
                }

            val result = userSessionsDb.login("logintest", "password").getOrElse { throw it }

            assertNotNull(result)
            assertTrue(result.rawToken.isNotBlank())
            assertNotNull(result.session.uuid)
            assertEquals("logintest", result.session.user.username)
            assertNull(result.session.revokedAt)
            assertTrue(result.session.expiresAt.isAfter(Instant.now()))
        }
    }

    @Test
    fun `login returns null for incorrect password`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()

        resourceScope {
            val db = Database.connect(dbPath).bind()
            val usersDb = UsersDatabase(db)
            val userSessionsDb = UserSessionsDatabase(db, usersDb)

            val _ =
                usersDb.createUser(username = "logintest2", password = "password").getOrElse {
                    throw it
                }

            val result = userSessionsDb.login("logintest2", "wrongpassword").getOrElse { throw it }
            assertNull(result)
        }
    }

    @Test
    fun `login returns null for non-existent user`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()

        resourceScope {
            val db = Database.connect(dbPath).bind()
            val usersDb = UsersDatabase(db)
            val userSessionsDb = UserSessionsDatabase(db, usersDb)

            val result = userSessionsDb.login("nobody", "password").getOrElse { throw it }
            assertNull(result)
        }
    }

    @Test
    fun `authorize returns UserSession for valid raw token`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()

        resourceScope {
            val db = Database.connect(dbPath).bind()
            val usersDb = UsersDatabase(db)
            val userSessionsDb = UserSessionsDatabase(db, usersDb)

            val _ =
                usersDb.createUser(username = "authuser", password = "password").getOrElse {
                    throw it
                }

            val loginResult = userSessionsDb.login("authuser", "password").getOrElse { throw it }
            assertNotNull(loginResult)

            val session = userSessionsDb.authorize(loginResult.rawToken).getOrElse { throw it }
            assertNotNull(session)
            assertEquals("authuser", session.user.username)
            assertEquals(loginResult.session.uuid, session.uuid)
        }
    }

    @Test
    fun `authorize returns null for invalid raw token`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()

        resourceScope {
            val db = Database.connect(dbPath).bind()
            val usersDb = UsersDatabase(db)
            val userSessionsDb = UserSessionsDatabase(db, usersDb)

            val result = userSessionsDb.authorize("nonexistent-token").getOrElse { throw it }
            assertNull(result)
        }
    }

    @Test
    fun `authorize returns null for expired session and deletes it`(@TempDir tempDir: Path) =
        runTest {
            val dbPath = tempDir.resolve("test.db").toString()

            resourceScope {
                val db = Database.connect(dbPath).bind()
                val usersDb = UsersDatabase(db)
                val userSessionsDb = UserSessionsDatabase(db, usersDb)

                val createResult =
                    usersDb.createUser(username = "expireauth", password = "password").getOrElse {
                        throw it
                    }
                assertTrue(createResult is CreateResult.Created)
                val user = createResult.toNullable!!

                // Create session already expired
                val sessionResult =
                    userSessionsDb
                        .createSession(
                            userUuid = user.uuid,
                            tokenHash = "expired-auth-hash",
                            expiresAt = Instant.now().minusSeconds(3600),
                        )
                        .getOrElse { throw it }
                assertTrue(sessionResult is CreateResult.Created)

                // authorize should return null (expired) and delete it
                val result = userSessionsDb.authorize("expired-auth-hash").getOrElse { throw it }
                assertNull(result)

                // Session should be deleted
                val notFound =
                    userSessionsDb
                        .findSessionByTokenHash(UserSessionsDatabase.hashToken("expired-auth-hash"))
                        .getOrElse { throw it }
                assertNull(notFound)
            }
        }

    @Test
    fun `authorize returns null for revoked session`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()

        resourceScope {
            val db = Database.connect(dbPath).bind()
            val usersDb = UsersDatabase(db)
            val userSessionsDb = UserSessionsDatabase(db, usersDb)

            val _ =
                usersDb.createUser(username = "revokeauth", password = "password").getOrElse {
                    throw it
                }

            val loginResult = userSessionsDb.login("revokeauth", "password").getOrElse { throw it }
            assertNotNull(loginResult)

            // Logout (revoke) the session
            val loggedOut = userSessionsDb.logout(loginResult.rawToken).getOrElse { throw it }
            assertTrue(loggedOut)

            // authorize should return null (revoked and deleted)
            val result = userSessionsDb.authorize(loginResult.rawToken).getOrElse { throw it }
            assertNull(result)
        }
    }

    @Test
    fun `logout is idempotent - second call returns false`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()

        resourceScope {
            val db = Database.connect(dbPath).bind()
            val usersDb = UsersDatabase(db)
            val userSessionsDb = UserSessionsDatabase(db, usersDb)

            val _ =
                usersDb.createUser(username = "idemuser", password = "password").getOrElse {
                    throw it
                }

            val loginResult = userSessionsDb.login("idemuser", "password").getOrElse { throw it }
            assertNotNull(loginResult)

            // First logout returns true
            val first = userSessionsDb.logout(loginResult.rawToken).getOrElse { throw it }
            assertTrue(first)

            // Second logout (same token) returns false
            val second = userSessionsDb.logout(loginResult.rawToken).getOrElse { throw it }
            assertFalse(second)
        }
    }

    @Test
    fun `multiple sessions for same user`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()

        resourceScope {
            val db = Database.connect(dbPath).bind()
            val usersDb = UsersDatabase(db)
            val userSessionsDb = UserSessionsDatabase(db, usersDb)

            val _ =
                usersDb.createUser(username = "multisession", password = "password").getOrElse {
                    throw it
                }

            // Login three times to create three sessions
            val session1 = userSessionsDb.login("multisession", "password").getOrElse { throw it }
            val session2 = userSessionsDb.login("multisession", "password").getOrElse { throw it }
            val session3 = userSessionsDb.login("multisession", "password").getOrElse { throw it }

            assertNotNull(session1)
            assertNotNull(session2)
            assertNotNull(session3)

            // Each session has a unique token
            assertTrue(session1.rawToken != session2.rawToken)
            assertTrue(session2.rawToken != session3.rawToken)
            assertTrue(session1.rawToken != session3.rawToken)

            // Each session can be authorized independently
            assertNotNull(userSessionsDb.authorize(session1.rawToken).getOrElse { throw it })
            assertNotNull(userSessionsDb.authorize(session2.rawToken).getOrElse { throw it })
            assertNotNull(userSessionsDb.authorize(session3.rawToken).getOrElse { throw it })
        }
    }

    @Test
    fun `token hash storage - raw token is not stored in database`(@TempDir tempDir: Path) =
        runTest {
            val dbPath = tempDir.resolve("test.db").toString()

            resourceScope {
                val db = Database.connect(dbPath).bind()
                val usersDb = UsersDatabase(db)
                val userSessionsDb = UserSessionsDatabase(db, usersDb)

                val _ =
                    usersDb.createUser(username = "hashtest", password = "password").getOrElse {
                        throw it
                    }

                val loginResult =
                    userSessionsDb.login("hashtest", "password").getOrElse { throw it }
                assertNotNull(loginResult)

                // Raw token should NOT match the stored token hash
                val session =
                    userSessionsDb
                        .findSessionByTokenHash(
                            UserSessionsDatabase.hashToken(loginResult.rawToken)
                        )
                        .getOrElse { throw it }
                assertNotNull(session)
                // The stored hash should be different from the raw token
                assertTrue(session.tokenHash != loginResult.rawToken)
                // But the raw token should hash to the stored hash
                assertEquals(
                    UserSessionsDatabase.hashToken(loginResult.rawToken),
                    session.tokenHash,
                )
            }
        }
}
