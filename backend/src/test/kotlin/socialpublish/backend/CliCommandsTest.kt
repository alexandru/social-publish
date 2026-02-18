package socialpublish.backend

import com.github.ajalt.clikt.testing.test
import arrow.core.getOrElse
import arrow.fx.coroutines.resourceScope
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import socialpublish.backend.db.Database
import socialpublish.backend.db.UsersDatabase
import socialpublish.backend.db.query

class CliCommandsTest {

    @Test
    fun `gen-bcrypt-hash with --password option should work`() {
        val password = "testpassword123"

        val result = SocialPublishCli().test("gen-bcrypt-hash --password $password")

        assertEquals(0, result.statusCode)

        // Should not prompt
        assertTrue(!result.stdout.contains("Enter password"))
        assertTrue(result.stdout.contains("BCrypt hash:"))

        // Extract the hash from output
        val lines = result.stdout.trim().lines()
        val hashLine = lines.firstOrNull { it.startsWith("$") }

        // BCrypt hashes start with $2a$, $2b$, or $2y$ and are 60 characters
        assertTrue(hashLine != null)
        assertTrue(hashLine.startsWith("$2"))
        assertTrue(hashLine.length == 60)
    }

    @Test
    fun `gen-bcrypt-hash with --quiet should output only the hash`() {
        val password = "testpassword123"

        val result = SocialPublishCli().test("gen-bcrypt-hash --quiet --password $password")

        assertEquals(0, result.statusCode)

        // Should not contain extra messages
        assertTrue(!result.stdout.contains("BCrypt hash:"))

        // Output should be just the hash
        val hash = result.stdout.trim()
        assertTrue(hash.startsWith("$2"))
        assertTrue(hash.length == 60)
    }

    @Test
    fun `main command should show help when no subcommand is given`() {
        val result = SocialPublishCli().test("")

        // Should show help
        assertTrue(result.stdout.contains("Usage:") || result.stderr.contains("Usage:"))
        val output = result.stdout + result.stderr
        assertTrue(output.contains("start-server"))
        assertTrue(output.contains("gen-bcrypt-hash"))
    }

    @Test
    fun `start-server should fail without required options`() {
        val result = SocialPublishCli().test("start-server")

        // Should fail due to missing required options
        assertTrue(result.statusCode != 0)
        val output = result.stdout + result.stderr
        assertTrue(
            output.contains("Error") || output.contains("Missing") || output.contains("required")
        )
    }

    @Test
    fun `change-password updates password for existing user`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()

        resourceScope {
            val db = Database.connect(dbPath).bind()
            val usersDb = UsersDatabase(db)
            val _ = usersDb.createUser("alice", "oldpass").getOrElse { throw it }

            val result =
                SocialPublishCli()
                    .test(
                        "change-password --db-path $dbPath --username alice --new-password newpass"
                    )

            assertEquals(0, result.statusCode)
            assertTrue(result.stdout.contains("Password changed successfully"))

            val valid = usersDb.verifyPassword("alice", "newpass").getOrElse { throw it }
            assertTrue(valid)
        }
    }

    @Test
    fun `change-password enables login for null-password user`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()

        resourceScope {
            val db = Database.connect(dbPath).bind()
            val usersDb = UsersDatabase(db)
            val _ = usersDb.createUser("bob", "initial").getOrElse { throw it }
            val _ = db.query("UPDATE users SET password_hash = NULL WHERE username = ?") {
                setString(1, "bob")
                executeUpdate()
            }

            val before = usersDb.verifyPassword("bob", "restored").getOrElse { throw it }
            assertFalse(before)

            val result =
                SocialPublishCli()
                    .test(
                        "change-password --db-path $dbPath --username bob --new-password restored"
                    )

            assertEquals(0, result.statusCode)

            val after = usersDb.verifyPassword("bob", "restored").getOrElse { throw it }
            assertTrue(after)
        }
    }
}
