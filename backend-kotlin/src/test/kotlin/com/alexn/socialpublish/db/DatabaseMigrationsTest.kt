package com.alexn.socialpublish.db

import kotlinx.coroutines.test.runTest
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.KotlinPlugin
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertTrue

class DatabaseMigrationsTest {
    @Test
    fun `database migrations create required tables`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()
        val jdbi = Jdbi.create("jdbc:sqlite:$dbPath").installPlugin(KotlinPlugin())

        // Run the same migrations as production
        Database.migrate(jdbi)

        jdbi.useHandle<Exception> { handle ->
            val tables =
                handle.createQuery("SELECT name FROM sqlite_master WHERE type='table'")
                    .mapTo(String::class.java)
                    .list()

            assertTrue(tables.contains("documents"))
            assertTrue(tables.contains("document_tags"))
            assertTrue(tables.contains("uploads"))
        }

        jdbi.useHandle<Exception> { handle ->
            val tables =
                handle.createQuery("SELECT name FROM sqlite_master WHERE type='table'")
                    .mapTo(String::class.java)
                    .list()

            assertTrue(tables.contains("documents"))
            assertTrue(tables.contains("document_tags"))
            assertTrue(tables.contains("uploads"))
        }
    }
}
