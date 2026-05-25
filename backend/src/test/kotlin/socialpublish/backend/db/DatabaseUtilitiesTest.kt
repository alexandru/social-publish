package socialpublish.backend.db

import arrow.core.getOrElse
import arrow.core.raise.context.raise
import arrow.core.raise.either
import arrow.fx.coroutines.resourceScope
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Comprehensive tests for database utilities in Database.kt.
 *
 * These tests verify:
 * - Connection management and resource cleanup
 * - Transaction commit and rollback behavior
 * - Constraint violation error handling (transactionForUpdates)
 * - Query execution and result mapping
 */
class DatabaseUtilitiesTest {

    @Test
    fun `connection is properly acquired and released`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()

        resourceScope {
            val db = Database.connect(dbPath).bind()

            // Acquire a connection within resourceScope
            resourceScope {
                val conn = db.connection().bind()
                // Verify connection is usable
                val result =
                    either {
                            conn.query("SELECT 1") {
                                executeQuery().safe().firstOrNull { it.getInt(1) }
                            }
                        }
                        .getOrElse { throw it }
                assertEquals(1, result)
            }

            // Connection should be returned to pool and reusable
            resourceScope {
                val conn = db.connection().bind()
                val result =
                    either {
                            conn.query("SELECT 2") {
                                executeQuery().safe().firstOrNull { it.getInt(1) }
                            }
                        }
                        .getOrElse { throw it }
                assertEquals(2, result)
            }
        }
    }

    @Test
    fun `transaction commits on success`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()

        resourceScope {
            val db = Database.connect(dbPath).bind()

            // Create a test table and insert data in a transaction
            db.transaction {
                    query("CREATE TABLE test_commits (id INTEGER PRIMARY KEY, value TEXT)") {
                        execute()
                        Unit
                    }
                    query("INSERT INTO test_commits (id, value) VALUES (1, 'test')") {
                        execute()
                        Unit
                    }
                }
                .getOrElse { throw it }

            // Verify data was committed
            val result =
                either {
                        db.query("SELECT value FROM test_commits WHERE id = 1") {
                            executeQuery().safe().firstOrNull { it.getString("value") }
                        }
                    }
                    .getOrElse { throw it }
            assertEquals("test", result)
        }
    }

    @Test
    fun `transaction rolls back on exception`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()

        resourceScope {
            val db = Database.connect(dbPath).bind()

            // Create a test table
            db.transaction {
                    query("CREATE TABLE test_rollback (id INTEGER PRIMARY KEY, value TEXT)") {
                        execute()
                        Unit
                    }
                }
                .getOrElse { throw it }

            // Attempt a transaction that fails
            val result = db.transaction {
                query("INSERT INTO test_rollback (id, value) VALUES (1, 'test')") {
                    execute()
                    Unit
                }
                // Force a rollback
                throw RuntimeException("Forced error")
            }
            assertTrue(result.isLeft())

            // Verify data was NOT committed
            val count =
                either {
                        db.query("SELECT COUNT(*) as cnt FROM test_rollback") {
                            executeQuery().safe().firstOrNull { it.getInt("cnt") }
                        }
                    }
                    .getOrElse { throw it }
            assertEquals(0, count)
        }
    }

    @Test
    fun `transaction rolls back on raised database error`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()

        resourceScope {
            val db = Database.connect(dbPath).bind()

            db.transaction {
                    query("CREATE TABLE test_raise_rollback (id INTEGER PRIMARY KEY, value TEXT)") {
                        execute()
                        Unit
                    }
                }
                .getOrElse { throw it }

            val result =
                db.transaction<Unit> {
                    query("INSERT INTO test_raise_rollback (id, value) VALUES (1, 'test')") {
                        execute()
                        Unit
                    }
                    raise(DBException("Forced typed database error"))
                }
            assertTrue(result.isLeft())

            val count =
                either {
                        db.query("SELECT COUNT(*) as cnt FROM test_raise_rollback") {
                            executeQuery().safe().firstOrNull { it.getInt("cnt") }
                        }
                    }
                    .getOrElse { throw it }
            assertEquals(0, count)
        }
    }

    @Test
    fun `transactionForUpdates detects unique constraint violations`(@TempDir tempDir: Path) =
        runTest {
            val dbPath = tempDir.resolve("test.db").toString()

            resourceScope {
                val db = Database.connect(dbPath).bind()

                // Create a table with a unique constraint
                db.transaction {
                        query(
                            "CREATE TABLE test_unique (id INTEGER PRIMARY KEY, email TEXT UNIQUE)"
                        ) {
                            execute()
                            Unit
                        }
                        query(
                            "INSERT INTO test_unique (id, email) VALUES (1, 'test@example.com')"
                        ) {
                            execute()
                            Unit
                        }
                    }
                    .getOrElse { throw it }

                // Try to insert duplicate email
                val result = db.transactionForUpdates {
                    query("INSERT INTO test_unique (id, email) VALUES (2, 'test@example.com')") {
                        execute()
                        Unit
                    }
                }

                // Should return Left with UniqueViolation
                assertTrue(result.isLeft())
                val error = result.leftOrNull()
                assertNotNull(error)
                assertTrue(error is SqlUpdateException.UniqueViolation)
                assertTrue(error.message!!.contains("Unique constraint", ignoreCase = true))
            }
        }

    @Test
    fun `transactionForUpdates returns Right on success`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()

        resourceScope {
            val db = Database.connect(dbPath).bind()

            db.transaction {
                    query("CREATE TABLE test_success (id INTEGER PRIMARY KEY, value TEXT)") {
                        execute()
                        Unit
                    }
                }
                .getOrElse { throw it }

            val result = db.transactionForUpdates {
                query("INSERT INTO test_success (id, value) VALUES (1, 'success')") {
                    execute()
                    Unit
                }
                "completed"
            }

            assertTrue(result.isRight())
            assertEquals("completed", result.getOrElse { "failed" })
        }
    }

    @Test
    fun `query executes and returns results`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()

        resourceScope {
            val db = Database.connect(dbPath).bind()

            db.transaction {
                    query("CREATE TABLE test_query (id INTEGER PRIMARY KEY, name TEXT)") {
                        execute()
                        Unit
                    }
                    query(
                        "INSERT INTO test_query (id, name) VALUES (1, 'Alice'), (2, 'Bob'), (3, 'Charlie')"
                    ) {
                        execute()
                        Unit
                    }
                }
                .getOrElse { throw it }

            // Test query with parameter
            val name =
                either {
                        db.query("SELECT name FROM test_query WHERE id = ?") {
                            setInt(1, 2)
                            executeQuery().safe().firstOrNull { it.getString("name") }
                        }
                    }
                    .getOrElse { throw it }
            assertEquals("Bob", name)
        }
    }

    @Test
    fun `toList maps all rows correctly`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()

        resourceScope {
            val db = Database.connect(dbPath).bind()

            db.transaction {
                    query("CREATE TABLE test_list (id INTEGER PRIMARY KEY, name TEXT)") {
                        execute()
                        Unit
                    }
                    query(
                        "INSERT INTO test_list (id, name) VALUES (1, 'Alice'), (2, 'Bob'), (3, 'Charlie')"
                    ) {
                        execute()
                        Unit
                    }
                }
                .getOrElse { throw it }

            // Test toList
            val names =
                either {
                        db.query("SELECT name FROM test_list ORDER BY id") {
                            executeQuery().safe().toList { it.getString("name") }
                        }
                    }
                    .getOrElse { throw it }

            assertEquals(3, names.size)
            assertEquals(listOf("Alice", "Bob", "Charlie"), names)
        }
    }

    @Test
    fun `toList returns empty list for no results`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()

        resourceScope {
            val db = Database.connect(dbPath).bind()

            db.transaction {
                    query("CREATE TABLE test_empty (id INTEGER PRIMARY KEY)") {
                        execute()
                        Unit
                    }
                }
                .getOrElse { throw it }

            val results =
                either {
                        db.query("SELECT * FROM test_empty") {
                            executeQuery().safe().toList { it.getInt("id") }
                        }
                    }
                    .getOrElse { throw it }

            assertTrue(results.isEmpty())
        }
    }

    @Test
    fun `firstOrNull returns first row when exists`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()

        resourceScope {
            val db = Database.connect(dbPath).bind()

            db.transaction {
                    query("CREATE TABLE test_first (id INTEGER PRIMARY KEY, name TEXT)") {
                        execute()
                        Unit
                    }
                    query("INSERT INTO test_first (id, name) VALUES (1, 'First'), (2, 'Second')") {
                        execute()
                        Unit
                    }
                }
                .getOrElse { throw it }

            val first =
                either {
                        db.query("SELECT name FROM test_first ORDER BY id") {
                            executeQuery().safe().firstOrNull { it.getString("name") }
                        }
                    }
                    .getOrElse { throw it }

            assertEquals("First", first)
        }
    }

    @Test
    fun `firstOrNull returns null when no rows`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()

        resourceScope {
            val db = Database.connect(dbPath).bind()

            db.transaction {
                    query("CREATE TABLE test_null (id INTEGER PRIMARY KEY)") {
                        execute()
                        Unit
                    }
                }
                .getOrElse { throw it }

            val result =
                either {
                        db.query("SELECT * FROM test_null") {
                            executeQuery().safe().firstOrNull { it.getInt("id") }
                        }
                    }
                    .getOrElse { throw it }

            assertEquals(null, result)
        }
    }

    @Test
    fun `SafeConnection query executes within existing transaction`(@TempDir tempDir: Path) =
        runTest {
            val dbPath = tempDir.resolve("test.db").toString()

            resourceScope {
                val db = Database.connect(dbPath).bind()

                db.transaction {
                        query("CREATE TABLE test_conn (id INTEGER PRIMARY KEY, value TEXT)") {
                            execute()
                            Unit
                        }

                        // Execute multiple queries in same transaction via SafeConnection
                        query("INSERT INTO test_conn (id, value) VALUES (1, 'one')") {
                            execute()
                            Unit
                        }
                        query("INSERT INTO test_conn (id, value) VALUES (2, 'two')") {
                            execute()
                            Unit
                        }

                        val count =
                            query("SELECT COUNT(*) as cnt FROM test_conn") {
                                executeQuery().safe().firstOrNull { it.getInt("cnt") }
                            }

                        assertEquals(2, count)
                    }
                    .getOrElse { throw it }
            }
        }

    @Test
    fun `multiple transactions can be executed sequentially`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()

        resourceScope {
            val db = Database.connect(dbPath).bind()

            // First transaction
            db.transaction {
                    query("CREATE TABLE test_multi (id INTEGER PRIMARY KEY, value TEXT)") {
                        execute()
                        Unit
                    }
                    query("INSERT INTO test_multi (id, value) VALUES (1, 'first')") {
                        execute()
                        Unit
                    }
                }
                .getOrElse { throw it }

            // Second transaction
            db.transaction {
                    query("INSERT INTO test_multi (id, value) VALUES (2, 'second')") {
                        execute()
                        Unit
                    }
                }
                .getOrElse { throw it }

            // Third transaction to verify
            val count =
                db.transaction {
                        query("SELECT COUNT(*) as cnt FROM test_multi") {
                            executeQuery().safe().firstOrNull { it.getInt("cnt") }
                        }
                    }
                    .getOrElse { throw it }

            assertEquals(2, count)
        }
    }

    @Test
    fun `Database clock is accessible`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()

        resourceScope {
            val db = Database.connect(dbPath).bind()

            // Verify clock is available and usable
            val now = db.clock.instant()
            assertNotNull(now)
        }
    }
}
