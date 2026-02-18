package socialpublish.backend.db

import arrow.core.getOrElse
import arrow.fx.coroutines.resourceScope
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
            // Verify the default password is hashed (BCrypt)
            assertTrue(
                admin.passwordHash.startsWith("\$2a\$") ||
                    admin.passwordHash.startsWith("\$2b\$") ||
                    admin.passwordHash.startsWith("\$2y\$"),
                "Admin password hash should be a valid BCrypt hash",
            )
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
