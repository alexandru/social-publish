package socialpublish.backend.db

import arrow.fx.coroutines.resourceScope
import java.nio.file.Path
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
}
