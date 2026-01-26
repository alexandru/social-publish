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
 * Database connection with HikariCP pooling.
 *
 * @property clock Clock instance for time-based operations (useful for testing)
 */
data class Database(val dataSource: DataSource, val clock: Clock, val dbPath: String) {
    companion object {
        /** Dispatcher used for all database operations (virtual threads). */
        val Dispatcher = Dispatchers.LOOM

        /**
         * Creates a database connection and runs migrations.
         *
         * Creates parent directories and the database file if they don't exist. Runs all pending
         * migrations before returning.
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

        /**
         * Creates an unmanaged database connection for testing.
         *
         * Unlike [connect], this does not use Arrow Resource management and returns the Database
         * directly. The caller is responsible for closing the connection pool when done, though in
         * practice test JVMs exit quickly so this is rarely necessary.
         *
         * This is intended for test scenarios where the database needs to outlive a single scope.
         */
        suspend fun connectUnmanaged(dbPath: String): Database {
            logger.info { "Connecting to database (unmanaged) at $dbPath" }
            val dbFile = File(dbPath)
            dbFile.parentFile?.mkdirs()

            val hikariConfig = createHikariConfig(dbPath)
            val dataSource = withContext(Database.Dispatcher) { HikariDataSource(hikariConfig) }
            val db = Database(dataSource = dataSource, clock = Clock.systemUTC(), dbPath = dbPath)

            // Run migrations
            migrate(db).getOrElse { throw it }
            logger.info { "Database connected and migrated (unmanaged)" }
            return db
        }
    }
}

/** Bundle of database access objects. */
data class DatabaseResources(
    val db: Database,
    val documentsDb: DocumentsDatabase,
    val postsDb: PostsDatabase,
    val filesDb: FilesDatabase,
    val usersDb: UsersDatabase,
)

/** Factory for creating database resource bundles. */
object DatabaseBundle {
    /** Creates a bundle with all database access objects. */
    fun resource(dbPath: String): Resource<DatabaseResources> = resource {
        val db = Database.connect(dbPath).bind()
        val documentsDb = DocumentsDatabase(db)
        val postsDb = PostsDatabase(documentsDb)
        val filesDb = FilesDatabase(db)
        val usersDb = UsersDatabase(db)
        DatabaseResources(
            db = db,
            documentsDb = documentsDb,
            postsDb = postsDb,
            filesDb = filesDb,
            usersDb = usersDb,
        )
    }
}

/** Type-safe wrapper around JDBC Connection (zero runtime overhead). */
@JvmInline value class SafeConnection(val connection: Connection)

fun Connection.safe(): SafeConnection = SafeConnection(this)

/** Type-safe wrapper around JDBC PreparedStatement (zero runtime overhead). */
@JvmInline value class SafePreparedStatement(val statement: PreparedStatement)

fun PreparedStatement.safe(): SafePreparedStatement = SafePreparedStatement(this)

/** Type-safe wrapper around JDBC ResultSet (zero runtime overhead). */
@JvmInline value class SafeResultSet(val resultSet: ResultSet)

fun ResultSet.safe(): SafeResultSet = SafeResultSet(this)

/** Creates HikariConfig for SQLite database. */
private fun createHikariConfig(dbPath: String): HikariConfig =
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

/** Creates a HikariCP connection pool for SQLite. */
fun createDataSource(dbPath: String): Resource<DataSource> = resource {
    install(
        {
            val cfg = createHikariConfig(dbPath)
            withContext(Database.Dispatcher) { HikariDataSource(cfg) }
        },
        { p, _ -> withContext(Database.Dispatcher) { p.close() } },
    )
}

/**
 * Acquires a connection from the pool.
 *
 * Connection is automatically returned to the pool when the Resource is released.
 */
fun Database.connection(): Resource<SafeConnection> = resource {
    install(
        { withContext(Database.Dispatcher) { SafeConnection(dataSource.connection) } },
        { c, _ -> withContext(Database.Dispatcher) { c.connection.close() } },
    )
}

/**
 * Executes a block within a transaction.
 *
 * Automatically commits on success and rolls back on exception. Auto-commit is disabled during
 * execution and restored afterwards.
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
 * Executes a transaction with typed constraint violation handling.
 *
 * Returns `Either<SqlUpdateException, A>` for handling constraint violations. Useful for
 * INSERT/UPDATE operations.
 *
 * SQLite note: Unlike PostgreSQL, constraint violations are detected by parsing error messages, so
 * table/column information may not always be available.
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

/** Extracts constraint name from SQLite error messages. */
private fun extractConstraintName(message: String?): String? {
    if (message == null) return null
    // Try to extract constraint name from SQLite error messages
    // Example: "UNIQUE constraint failed: users.email"
    val pattern = """constraint\s+(?:failed:\s+)?([^\s,]+)""".toRegex(RegexOption.IGNORE_CASE)
    return pattern.find(message)?.groupValues?.get(1)
}

/**
 * Executes a PreparedStatement with coroutine cancellation support.
 *
 * If the coroutine is cancelled, the SQL statement is cancelled and resources are cleaned up
 * properly.
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

/** Executes a SQL query on an existing connection. */
suspend fun <A> SafeConnection.query(
    @Language("SQL") sql: String,
    block: suspend PreparedStatement.() -> A,
): A =
    withContext(Database.Dispatcher) {
        @Suppress("SqlSourceToSinkFlow")
        connection.prepareStatement(sql.trimIndent()).safe().useWithInterruption(block)
    }

/**
 * Executes a SQL query with automatic connection management.
 *
 * For multiple queries in a transaction, use [transaction] instead.
 */
suspend fun <A> Database.query(
    @Language("SQL") sql: String,
    block: suspend PreparedStatement.() -> A,
): A = resourceScope { connection().bind().query(sql, block) }

/**
 * Maps all rows in a ResultSet to a List.
 *
 * Example:
 * ```kotlin
 * val names = resultSet.safe().toList { rs -> rs.getString("name") }
 * ```
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
 * Extracts the first row from a ResultSet, or null if empty.
 *
 * Example:
 * ```kotlin
 * val user = resultSet.safe().firstOrNull { rs ->
 *     User(rs.getString("id"), rs.getString("name"))
 * }
 * ```
 */
fun <A> SafeResultSet.firstOrNull(f: (ResultSet) -> A): A? =
    resultSet.use { if (resultSet.next()) f(resultSet) else null }

/**
 * Runs all pending database migrations.
 *
 * Migrations are idempotent - safe to call multiple times. Already-applied migrations are skipped.
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
