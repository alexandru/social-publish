package socialpublish.backend.db

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import arrow.fx.coroutines.Resource
import arrow.fx.coroutines.resource
import arrow.fx.coroutines.resourceScope
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Clock
import javax.sql.DataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.intellij.lang.annotations.Language
import socialpublish.backend.utils.LOOM

private val logger = KotlinLogging.logger {}

/**
 * Main database connection container with HikariCP connection pooling.
 *
 * This class provides a lightweight abstraction over JDBC with Arrow's Resource management for
 * safe, automatic resource cleanup. It uses HikariCP for connection pooling configured specifically
 * for SQLite with low memory usage.
 *
 * @property dataSource The HikariCP data source managing the connection pool
 * @property clock Clock instance for time-based operations (useful for testing)
 * @property dbPath Path to the SQLite database file
 */
data class Database(val dataSource: DataSource, val clock: Clock, val dbPath: String) {
    companion object {
        /**
         * Dispatcher used for all database operations. Uses virtual threads (Dispatchers.LOOM) for
         * efficient concurrent I/O operations.
         */
        val Dispatcher = Dispatchers.LOOM

        /**
         * Creates a new database connection with automatic migration.
         *
         * This function:
         * 1. Creates the database file and parent directories if they don't exist
         * 2. Initializes a HikariCP connection pool
         * 3. Runs all pending migrations
         * 4. Returns a Database instance wrapped in Arrow's Resource for safe cleanup
         *
         * @param dbPath Path to the SQLite database file
         * @return Resource<Database> that must be bound in a resourceScope
         */
        fun connect(dbPath: String): Resource<Database> = resource {
            logger.info { "Connecting to database at $dbPath" }
            val dbFile = File(dbPath)
            dbFile.parentFile?.mkdirs()

            val dataSource = createDataSource(dbPath).bind()
            val db = Database(dataSource = dataSource, clock = Clock.systemUTC(), dbPath = dbPath)

            // Run migrations
            migrate(db).getOrElse { throw it }
            logger.info { "Database connected and migrated" }
            db
        }
    }
}

/**
 * Bundle of all database access objects for convenient resource management.
 *
 * @property db The main Database instance
 * @property documentsDb Documents database access object
 * @property postsDb Posts database access object
 * @property filesDb Files database access object
 */
data class DatabaseResources(
    val db: Database,
    val documentsDb: DocumentsDatabase,
    val postsDb: PostsDatabase,
    val filesDb: FilesDatabase,
)

/**
 * Factory for creating a complete database resource bundle.
 *
 * This provides all database access objects in a single Resource for convenient initialization and
 * cleanup.
 */
object DatabaseBundle {
    /**
     * Creates a resource containing all database access objects.
     *
     * @param dbPath Path to the SQLite database file
     * @return Resource<DatabaseResources> with all database access objects
     */
    fun resource(dbPath: String): Resource<DatabaseResources> = resource {
        val db = Database.connect(dbPath).bind()
        val documentsDb = DocumentsDatabase(db)
        val postsDb = PostsDatabase(documentsDb)
        val filesDb = FilesDatabase(db)
        DatabaseResources(db = db, documentsDb = documentsDb, postsDb = postsDb, filesDb = filesDb)
    }
}

/**
 * Type-safe wrapper around JDBC Connection.
 *
 * This inline value class provides type safety without runtime overhead, preventing accidental
 * mixing of raw and wrapped connections.
 */
@JvmInline value class SafeConnection(val connection: Connection)

/**
 * Wraps a JDBC Connection in a type-safe wrapper.
 *
 * @return SafeConnection wrapper
 */
fun Connection.safe(): SafeConnection = SafeConnection(this)

/**
 * Type-safe wrapper around JDBC PreparedStatement.
 *
 * This inline value class provides type safety without runtime overhead.
 */
@JvmInline value class SafePreparedStatement(val statement: PreparedStatement)

/**
 * Wraps a JDBC PreparedStatement in a type-safe wrapper.
 *
 * @return SafePreparedStatement wrapper
 */
fun PreparedStatement.safe(): SafePreparedStatement = SafePreparedStatement(this)

/**
 * Type-safe wrapper around JDBC ResultSet.
 *
 * This inline value class provides type safety without runtime overhead.
 */
@JvmInline value class SafeResultSet(val resultSet: ResultSet)

/**
 * Wraps a JDBC ResultSet in a type-safe wrapper.
 *
 * @return SafeResultSet wrapper
 */
fun ResultSet.safe(): SafeResultSet = SafeResultSet(this)

/**
 * Creates a HikariCP DataSource configured for SQLite.
 *
 * Configuration details:
 * - Maximum pool size: 3 (low for SQLite to minimize memory usage)
 * - Minimum idle: 1
 * - Initialization timeout: 0 (doesn't throw if initial connection fails)
 *
 * @param dbPath Path to the SQLite database file
 * @return Resource<DataSource> that manages the connection pool lifecycle
 */
fun createDataSource(dbPath: String): Resource<DataSource> = resource {
    install(
        {
            val cfg =
                HikariConfig().apply {
                    jdbcUrl = "jdbc:sqlite:$dbPath"
                    driverClassName = "org.sqlite.JDBC"
                    // Keep pool size low for SQLite
                    maximumPoolSize = 3
                    minimumIdle = 1
                    // Instructs HikariCP to not throw if the pool cannot be seeded
                    // with an initial connection
                    initializationFailTimeout = 0
                }
            withContext(Database.Dispatcher) { HikariDataSource(cfg) }
        },
        { p, _ -> withContext(Database.Dispatcher) { p.close() } },
    )
}

/**
 * Acquires a database connection from the pool wrapped in Arrow's Resource.
 *
 * The connection is automatically returned to the pool when the Resource is released. All
 * operations are performed on the virtual thread dispatcher for efficient I/O.
 *
 * @return Resource<SafeConnection> that automatically closes the connection
 */
fun Database.connection(): Resource<SafeConnection> = resource {
    install(
        { withContext(Database.Dispatcher) { SafeConnection(dataSource.connection) } },
        { c, _ -> withContext(Database.Dispatcher) { c.connection.close() } },
    )
}

/**
 * Executes a block of code within a database transaction.
 *
 * The transaction:
 * - Disables auto-commit before execution
 * - Commits if the block completes successfully
 * - Rolls back if an exception is thrown
 * - Restores auto-commit in all cases
 *
 * The connection is automatically acquired from the pool and released after use.
 *
 * @param A The return type of the transaction block
 * @param block The code to execute within the transaction
 * @return The result of the block execution
 * @throws Exception if the block throws or the transaction fails
 */
suspend fun <A> Database.transaction(block: suspend SafeConnection.() -> A): A = resourceScope {
    val ref = connection().bind()
    try {
        ref.connection.autoCommit = false
        val result = block(ref)
        ref.connection.commit()
        result
    } catch (e: Exception) {
        ref.connection.rollback()
        throw e
    } finally {
        ref.connection.autoCommit = true
    }
}

/**
 * Executes a block of code within a transaction with SQLite constraint violation handling.
 *
 * This function wraps [transaction] and catches SQL exceptions, converting them into typed
 * [SqlUpdateException] instances for better error handling. It's particularly useful for
 * INSERT/UPDATE operations where constraint violations are expected.
 *
 * SQLite-specific notes:
 * - Unlike PostgreSQL, SQLite doesn't provide structured error codes
 * - Constraint violations are detected by parsing error messages
 * - Table and column information may not always be available
 *
 * @param A The return type of the transaction block
 * @param block The code to execute within the transaction
 * @return Either<SqlUpdateException, A> - Left with exception details or Right with result
 */
suspend fun <A> Database.transactionForUpdates(
    block: suspend SafeConnection.() -> A
): Either<SqlUpdateException, A> =
    try {
        transaction(block).right()
    } catch (e: SqlUpdateException) {
        e.left()
    } catch (e: SQLException) {
        // SQLite error handling
        when {
            e.message?.contains("UNIQUE constraint", ignoreCase = true) == true -> {
                // Extract constraint name from error message if possible
                val constraintName = extractConstraintName(e.message)
                SqlUpdateException.UniqueViolation(null, null, constraintName, e).left()
            }
            e.message?.contains("FOREIGN KEY constraint", ignoreCase = true) == true -> {
                val constraintName = extractConstraintName(e.message)
                SqlUpdateException.ForeignKeyViolation(null, null, constraintName, e).left()
            }
            e.message?.contains("CHECK constraint", ignoreCase = true) == true -> {
                val constraintName = extractConstraintName(e.message)
                SqlUpdateException.CheckViolation(null, null, constraintName, e).left()
            }
            else -> {
                SqlUpdateException.Unknown(e.message ?: "Unknown SQL error", e).left()
            }
        }
    }

/**
 * Extracts constraint name from SQLite error messages.
 *
 * Example message: "UNIQUE constraint failed: users.email" Extracted name: "users.email"
 *
 * @param message The SQL exception message
 * @return The constraint name if found, null otherwise
 */
private fun extractConstraintName(message: String?): String? {
    if (message == null) return null
    // Try to extract constraint name from SQLite error messages
    // Example: "UNIQUE constraint failed: users.email"
    val pattern = """constraint\s+(?:failed:\s+)?([^\s,]+)""".toRegex(RegexOption.IGNORE_CASE)
    return pattern.find(message)?.groupValues?.get(1)
}

/**
 * Executes a PreparedStatement with support for coroutine cancellation.
 *
 * This function:
 * 1. Acquires the SQL statement resource
 * 2. Executes the block in an async task for cancellation support
 * 3. Cancels the SQL statement if the coroutine is cancelled
 * 4. Automatically releases the statement resource
 *
 * The cancellation handling ensures that long-running queries can be interrupted without leaving
 * resources in an inconsistent state.
 *
 * @param A The return type of the block
 * @param block The code to execute with the PreparedStatement
 * @return The result of the block execution
 * @throws CancellationException if the coroutine is cancelled
 */
suspend fun <A> SafePreparedStatement.useWithInterruption(
    block: suspend PreparedStatement.() -> A
): A =
    withContext(Database.Dispatcher) {
        // (1) Acquire the resource (SQL statement)
        statement.use { statement ->
            // (2) Start a concurrent task for interruption support
            val task = async {
                try {
                    block(statement)
                } catch (e: Exception) {
                    // Filter cancellation to avoid logging downstream
                    if (!isActive) throw CancellationException("Query cancelled", e) else throw e
                }
            }
            // (3) Wait for result
            try {
                task.await()
            } catch (e: CancellationException) {
                // (4) Handle cancellation
                // (5) Cancel the SQL statement
                try {
                    statement.cancel()
                } catch (e2: Throwable) {
                    e.addSuppressed(e2)
                }
                // (6) Cancel the task
                task.cancelAndJoin()
                throw e
            }
        }
    }

/**
 * Executes a SQL query on an existing connection.
 *
 * The PreparedStatement is automatically created, used, and closed. Supports coroutine cancellation
 * through [useWithInterruption].
 *
 * @param A The return type of the query block
 * @param sql The SQL query string (supports IntelliJ SQL injection)
 * @param block The code to execute with the PreparedStatement
 * @return The result of the block execution
 */
suspend fun <A> SafeConnection.query(
    @Language("SQL") sql: String,
    block: suspend PreparedStatement.() -> A,
): A =
    withContext(Database.Dispatcher) {
        @Suppress("SqlSourceToSinkFlow")
        connection.prepareStatement(sql).safe().useWithInterruption(block)
    }

/**
 * Executes a SQL query with automatic connection management.
 *
 * This is a convenience function that acquires a connection from the pool, executes the query, and
 * releases the connection automatically.
 *
 * For multiple queries in a transaction, use [transaction] instead.
 *
 * @param A The return type of the query block
 * @param sql The SQL query string (supports IntelliJ SQL injection)
 * @param block The code to execute with the PreparedStatement
 * @return The result of the block execution
 */
suspend fun <A> Database.query(
    @Language("SQL") sql: String,
    block: suspend PreparedStatement.() -> A,
): A = resourceScope { connection().bind().query(sql, block) }

/**
 * Converts a ResultSet to a List by applying a mapping function to each row.
 *
 * The ResultSet is automatically closed after iteration.
 *
 * Example:
 * ```kotlin
 * val names = resultSet.safe().toList { rs -> rs.getString("name") }
 * ```
 *
 * @param A The type of elements in the resulting list
 * @param f Function to extract data from each ResultSet row
 * @return List of mapped values
 */
fun <A> SafeResultSet.toList(f: (ResultSet) -> A): List<A> =
    resultSet.use {
        val result = mutableListOf<A>()
        while (it.next()) {
            result.add(f(it))
        }
        result
    }

/**
 * Extracts the first row from a ResultSet or returns null if empty.
 *
 * The ResultSet is automatically closed after reading.
 *
 * Example:
 * ```kotlin
 * val user = resultSet.safe().firstOrNull { rs ->
 *     User(rs.getString("id"), rs.getString("name"))
 * }
 * ```
 *
 * @param A The type of the result
 * @param f Function to extract data from the ResultSet
 * @return The mapped value if a row exists, null otherwise
 */
fun <A> SafeResultSet.firstOrNull(f: (ResultSet) -> A): A? =
    resultSet.use { if (resultSet.next()) f(resultSet) else null }

/**
 * Runs all pending database migrations.
 *
 * Migrations are defined in [migrations.kt] as a list of [Migration] objects. Each migration is
 * checked via its `testIfApplied` function before execution.
 *
 * This function is idempotent - it's safe to call multiple times. Already-applied migrations are
 * skipped automatically.
 *
 * @param db The database instance to migrate
 * @return Either<DBException, Unit> - Left if migration fails, Right(Unit) on success
 */
suspend fun migrate(db: Database): Either<DBException, Unit> =
    try {
        db.transaction { runMigrations() }
        Either.Right(Unit)
    } catch (e: Exception) {
        DBException("Migration failed", e).left()
    }

private suspend fun SafeConnection.runMigrations() {
    logger.info { "Running database migrations..." }

    // Apply all migrations in order
    migrations.forEachIndexed { index, migration ->
        if (!migration.testIfApplied(this)) {
            logger.info { "Applying migration $index" }
            migration.ddl.forEach { ddlStatement ->
                query(ddlStatement) {
                    execute()
                    Unit
                }
            }
        } else {
            logger.debug { "Migration $index already applied, skipping" }
        }
    }

    logger.info { "Database migrations completed" }
}
