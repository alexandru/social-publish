package socialpublish.backend.db

import arrow.core.getOrElse
import arrow.fx.coroutines.resourceScope
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.assertEquals
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
            db.query("SELECT name FROM sqlite_master WHERE type='table'") {
                val tables = executeQuery().safe().toList { rs -> rs.getString("name") }

                assertTrue(tables.contains("documents"))
                assertTrue(tables.contains("document_tags"))
                assertTrue(tables.contains("uploads"))
            }
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
                val _ = usersDb.createUser("another", "password")

                // Re-open the database to simulate re-running migrations
            }

            resourceScope {
                val db = Database.connect(dbPath).bind()
                val usersDb = UsersDatabase(db)

                // There should be exactly 2 users, not 3
                val count =
                    db.query("SELECT COUNT(*) FROM users") {
                        val rs = executeQuery()
                        if (rs.next()) rs.getInt(1) else 0
                    }
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
                db.query("PRAGMA table_info(users)") {
                    executeQuery().safe().toList { rs -> rs.getString("name") }
                }
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
                    db.query("PRAGMA table_info(documents)") {
                        executeQuery().safe().toList { rs -> rs.getString("name") }
                    }
                assertTrue(
                    documentColumns.contains("user_uuid"),
                    "documents table should have user_uuid column",
                )

                val uploadColumns =
                    db.query("PRAGMA table_info(uploads)") {
                        executeQuery().safe().toList { rs -> rs.getString("name") }
                    }
                assertTrue(
                    uploadColumns.contains("user_uuid"),
                    "uploads table should have user_uuid column",
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
        }
    }
}
