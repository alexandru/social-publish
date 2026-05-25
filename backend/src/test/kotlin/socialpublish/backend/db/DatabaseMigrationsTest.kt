package socialpublish.backend.db

import arrow.core.getOrElse
import arrow.core.raise.either
import arrow.fx.coroutines.resourceScope
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DatabaseMigrationsTest {
    @Test
    fun `database migrations create required tables`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()

        resourceScope {
            val db = Database.connect(dbPath).bind()

            // Verify tables exist
            either {
                    db.query("SELECT name FROM sqlite_master WHERE type='table'") {
                        val tables = executeQuery().safe().toList { rs -> rs.getString("name") }

                        assertTrue(tables.contains("documents"))
                        assertTrue(tables.contains("document_tags"))
                        assertTrue(tables.contains("uploads"))
                    }
                }
                .getOrElse { throw it }
        }
    }

    @Test
    fun `migration 5 creates admin user on fresh database`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()

        resourceScope {
            val db = Database.connect(dbPath).bind()
            val usersDb = UsersDatabase(db)

            val admin = usersDb.findByUsername("admin").getOrElse { throw it }
            assertNotNull(admin, "Admin user should be created on first run")
            assertEquals("admin", admin.username)
            assertNull(admin.passwordHash)
        }
    }

    @Test
    fun `migrations apply when users password_hash is NOT NULL from production`(
        @TempDir tempDir: Path
    ) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()

        createSchemaUpToMigration4(dbPath)

        resourceScope {
            val db = Database.connect(dbPath).bind()
            val usersDb = UsersDatabase(db)

            val admin = usersDb.findByUsername("admin").getOrElse { throw it }
            assertNotNull(admin, "Admin user should be created when migration 5 runs")
            assertNull(admin.passwordHash)
        }
    }

    @Test
    fun `users password_hash column is nullable`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()

        resourceScope {
            val db = Database.connect(dbPath).bind()
            val notNullFlag =
                either {
                        db.query("PRAGMA table_info(users)") {
                            val rs = executeQuery()
                            var value = -1
                            while (rs.next()) {
                                if (rs.getString("name") == "password_hash") {
                                    value = rs.getInt("notnull")
                                    break
                                }
                            }
                            value
                        }
                    }
                    .getOrElse { throw it }
            assertEquals(0, notNullFlag)
        }
    }

    @Test
    fun `migration 5 does not create admin user when users already exist`(@TempDir tempDir: Path) =
        runTest {
            val dbPath = tempDir.resolve("test.db").toString()

            resourceScope {
                val db = Database.connect(dbPath).bind()
                val usersDb = UsersDatabase(db)

                // The migration created the admin user; now create another user
                val _ = usersDb.createUser("another", "password").getOrElse { throw it }

                // Re-open the database to simulate re-running migrations
            }

            resourceScope {
                val db = Database.connect(dbPath).bind()
                // There should be exactly 2 users, not 3
                val count =
                    either {
                            db.query("SELECT COUNT(*) FROM users") {
                                val rs = executeQuery()
                                if (rs.next()) rs.getInt(1) else 0
                            }
                        }
                        .getOrElse { throw it }
                assertEquals(2, count, "Should not create a second admin user")
            }
        }

    @Test
    fun `migration 6 adds settings column to users table`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()

        resourceScope {
            val db = Database.connect(dbPath).bind()

            // Verify settings column exists
            val columns =
                either {
                        db.query("PRAGMA table_info(users)") {
                            executeQuery().safe().toList { rs -> rs.getString("name") }
                        }
                    }
                    .getOrElse { throw it }
            assertTrue(columns.contains("settings"), "users table should have a settings column")
        }
    }

    @Test
    fun `migration 7 adds user_uuid columns to documents and uploads`(@TempDir tempDir: Path) =
        runTest {
            val dbPath = tempDir.resolve("test.db").toString()

            resourceScope {
                val db = Database.connect(dbPath).bind()

                val documentColumns =
                    either {
                            db.query("PRAGMA table_info(documents)") {
                                executeQuery().safe().toList { rs -> rs.getString("name") }
                            }
                        }
                        .getOrElse { throw it }
                assertTrue(
                    documentColumns.contains("user_uuid"),
                    "documents table should have user_uuid column",
                )

                val uploadColumns =
                    either {
                            db.query("PRAGMA table_info(uploads)") {
                                executeQuery().safe().toList { rs -> rs.getString("name") }
                            }
                        }
                        .getOrElse { throw it }
                assertTrue(
                    uploadColumns.contains("user_uuid"),
                    "uploads table should have user_uuid column",
                )
            }
        }

    @Test
    fun `user sessions schema stores revocation time and no refresh token`(@TempDir tempDir: Path) =
        runTest {
            val dbPath = tempDir.resolve("test.db").toString()

            resourceScope {
                val db = Database.connect(dbPath).bind()

                val columns =
                    either {
                            db.query("PRAGMA table_info(user_sessions)") {
                                executeQuery().safe().toList { rs -> rs.getString("name") }
                            }
                        }
                        .getOrElse { throw it }
                assertFalse(
                    columns.contains("refresh_token_hash"),
                    "user_sessions should not have refresh_token_hash",
                )
                assertTrue(columns.contains("revoked_at"), "user_sessions should have revoked_at")

                val indexes =
                    either {
                            db.query("PRAGMA index_list(user_sessions)") {
                                executeQuery().safe().toList { rs -> rs.getString("name") }
                            }
                        }
                        .getOrElse { throw it }
                assertTrue(
                    indexes.contains("user_sessions_expires_at"),
                    "user_sessions should index expires_at",
                )
                assertTrue(
                    indexes.contains("user_sessions_revoked_at"),
                    "user_sessions should index revoked_at",
                )
            }
        }

    @Test
    fun `migration 9 converts existing user sessions schema`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()
        createSchemaUpToMigration4(dbPath)

        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
            conn.createStatement().use { statement ->
                statement.execute(
                    """
                    INSERT INTO users (uuid, username, password_hash, created_at, updated_at)
                    VALUES (
                        '018f0000-0000-7000-8000-000000000001',
                        'legacy',
                        'hash',
                        1000,
                        1000
                    )
                    """
                        .trimIndent()
                )
                statement.execute(
                    """
                    INSERT INTO user_sessions
                        (uuid, user_uuid, token_hash, refresh_token_hash, expires_at, created_at)
                    VALUES (
                        '018f0000-0000-7000-8000-000000000002',
                        '018f0000-0000-7000-8000-000000000001',
                        'legacy-token',
                        'legacy-refresh',
                        2000,
                        1000
                    )
                    """
                        .trimIndent()
                )
            }
        }

        resourceScope {
            val db = Database.connect(dbPath).bind()
            val usersDb = UsersDatabase(db)
            val userSessionsDb = UserSessionsDatabase(db, usersDb)

            val columns =
                either {
                        db.query("PRAGMA table_info(user_sessions)") {
                            executeQuery().safe().toList { rs -> rs.getString("name") }
                        }
                    }
                    .getOrElse { throw it }
            assertFalse(columns.contains("refresh_token_hash"))
            assertTrue(columns.contains("revoked_at"))

            val session =
                userSessionsDb.findSessionByTokenHash("legacy-token").getOrElse { throw it }
            assertNotNull(session)
            assertEquals("legacy", session.user.username)
            assertNull(session.revokedAt)

            val oldTableExists =
                either {
                        db.query(
                            """
                    SELECT 1
                    FROM sqlite_master
                    WHERE type = 'table' AND name = 'user_sessions_old'
                    """
                        ) {
                            executeQuery().next()
                        }
                    }
                    .getOrElse { throw it }
            assertFalse(oldTableExists, "migration 9 should drop user_sessions_old last")

            val indexes =
                either {
                        db.query("PRAGMA index_list(user_sessions)") {
                            executeQuery().safe().toList { rs -> rs.getString("name") }
                        }
                    }
                    .getOrElse { throw it }
            assertTrue(
                indexes.contains("user_sessions_expires_at"),
                "user_sessions should keep expires_at indexed after migration 9",
            )
            assertTrue(
                indexes.contains("user_sessions_revoked_at"),
                "user_sessions should index revoked_at after migration 9",
            )
        }
    }
}

private fun createSchemaUpToMigration4(dbPath: String) {
    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
        conn.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS documents (
                    uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                    search_key VARCHAR(255) UNIQUE NOT NULL,
                    kind VARCHAR(255) NOT NULL,
                    payload TEXT NOT NULL,
                    created_at INTEGER NOT NULL
                )
                """
                    .trimIndent()
            )
            statement.execute(
                """
                CREATE INDEX IF NOT EXISTS documents_created_at
                    ON documents(kind, created_at)
                """
                    .trimIndent()
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS document_tags (
                    document_uuid VARCHAR(36) NOT NULL,
                    name VARCHAR(255) NOT NULL,
                    kind VARCHAR(255) NOT NULL,
                    PRIMARY KEY (document_uuid, name, kind)
                )
                """
                    .trimIndent()
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS uploads (
                    uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                    hash VARCHAR(64) NOT NULL,
                    originalname VARCHAR(255) NOT NULL,
                    mimetype VARCHAR(255),
                    size INTEGER,
                    altText TEXT,
                    imageWidth INTEGER,
                    imageHeight INTEGER,
                    createdAt INTEGER NOT NULL
                )
                """
                    .trimIndent()
            )
            statement.execute(
                """
                CREATE INDEX IF NOT EXISTS uploads_createdAt
                    ON uploads(createdAt)
                """
                    .trimIndent()
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS users (
                    uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                    username VARCHAR(255) UNIQUE NOT NULL,
                    password_hash VARCHAR(255) NOT NULL,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """
                    .trimIndent()
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS user_sessions (
                    uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                    user_uuid VARCHAR(36) NOT NULL,
                    token_hash VARCHAR(255) UNIQUE NOT NULL,
                    refresh_token_hash VARCHAR(255),
                    expires_at INTEGER NOT NULL,
                    created_at INTEGER NOT NULL,
                    FOREIGN KEY (user_uuid) REFERENCES users(uuid) ON DELETE CASCADE
                )
                """
                    .trimIndent()
            )
            statement.execute(
                """
                CREATE INDEX IF NOT EXISTS user_sessions_expires_at
                    ON user_sessions(expires_at)
                """
                    .trimIndent()
            )
        }
    }
}
