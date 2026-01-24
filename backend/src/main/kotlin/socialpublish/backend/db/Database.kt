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

private val logger = KotlinLogging.logger {}

data class Database(val dataSource: DataSource, val clock: Clock, val dbPath: String) {
    companion object {
        val Dispatcher = Dispatchers.IO

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

/** Database connection wrapper using Arrow's Resource for safe resource management */
data class DatabaseResources(
    val db: Database,
    val documentsDb: DocumentsDatabase,
    val postsDb: PostsDatabase,
    val filesDb: FilesDatabase,
)

object DatabaseBundle {
    fun resource(dbPath: String): Resource<DatabaseResources> = resource {
        val db = Database.connect(dbPath).bind()
        val documentsDb = DocumentsDatabase(db)
        val postsDb = PostsDatabase(documentsDb)
        val filesDb = FilesDatabase(db)
        DatabaseResources(db = db, documentsDb = documentsDb, postsDb = postsDb, filesDb = filesDb)
    }
}

@JvmInline value class SafeConnection(val connection: Connection)

fun Connection.safe(): SafeConnection = SafeConnection(this)

@JvmInline value class SafePreparedStatement(val statement: PreparedStatement)

fun PreparedStatement.safe(): SafePreparedStatement = SafePreparedStatement(this)

@JvmInline value class SafeResultSet(val resultSet: ResultSet)

fun ResultSet.safe(): SafeResultSet = SafeResultSet(this)

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

fun Database.connection(): Resource<SafeConnection> = resource {
    install(
        { withContext(Database.Dispatcher) { SafeConnection(dataSource.connection) } },
        { c, _ -> withContext(Database.Dispatcher) { c.connection.close() } },
    )
}

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
 * Transaction wrapper for updates with SQLite-specific error handling.
 *
 * Note: This is adapted from PostgreSQL code and simplified for SQLite. SQLite doesn't have the
 * same level of constraint violation error codes as PostgreSQL, so we primarily rely on exception
 * messages and SQLITE_CONSTRAINT error codes.
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

private fun extractConstraintName(message: String?): String? {
    if (message == null) return null
    // Try to extract constraint name from SQLite error messages
    // Example: "UNIQUE constraint failed: users.email"
    val pattern = """constraint\s+(?:failed:\s+)?([^\s,]+)""".toRegex(RegexOption.IGNORE_CASE)
    return pattern.find(message)?.groupValues?.get(1)
}

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

suspend fun <A> SafeConnection.query(
    @Language("SQL") sql: String,
    block: suspend PreparedStatement.() -> A,
): A =
    withContext(Database.Dispatcher) {
        @Suppress("SqlSourceToSinkFlow")
        connection.prepareStatement(sql).safe().useWithInterruption(block)
    }

suspend fun <A> Database.query(
    @Language("SQL") sql: String,
    block: suspend PreparedStatement.() -> A,
): A = resourceScope { connection().bind().query(sql, block) }

fun <A> SafeResultSet.toList(f: (ResultSet) -> A): List<A> =
    resultSet.use {
        val result = mutableListOf<A>()
        while (it.next()) {
            result.add(f(it))
        }
        result
    }

fun <A> SafeResultSet.firstOrNull(f: (ResultSet) -> A): A? =
    resultSet.use { if (resultSet.next()) f(resultSet) else null }

/** Expose migrations so tests and other callers can reuse the same DDLs. */
suspend fun migrate(db: Database): Either<DBException, Unit> =
    try {
        db.transaction { runMigrations() }
        Either.Right(Unit)
    } catch (e: Exception) {
        DBException("Migration failed", e).left()
    }

private suspend fun SafeConnection.runMigrations() {
    logger.info { "Running database migrations..." }

    // Documents table migration
    if (!tableExists("documents")) {
        logger.info { "Creating documents table" }
        query("DROP TABLE IF EXISTS posts") {
            execute()
            Unit
        }
        query(
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
        ) {
            execute()
            Unit
        }
        query(
            """
            CREATE INDEX IF NOT EXISTS documents_created_at
            ON documents(kind, created_at)
            """
                .trimIndent()
        ) {
            execute()
            Unit
        }
    }

    // Document tags table migration
    if (!tableExists("document_tags")) {
        logger.info { "Creating document_tags table" }
        query(
            """
            CREATE TABLE IF NOT EXISTS document_tags (
                document_uuid VARCHAR(36) NOT NULL,
                name VARCHAR(255) NOT NULL,
                kind VARCHAR(255) NOT NULL,
                PRIMARY KEY (document_uuid, name, kind)
            )
            """
                .trimIndent()
        ) {
            execute()
            Unit
        }
    }

    // Uploads table migration
    if (!tableExists("uploads")) {
        logger.info { "Creating uploads table" }
        query(
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
        ) {
            execute()
            Unit
        }
        query(
            """
            CREATE INDEX IF NOT EXISTS uploads_createdAt
                ON uploads(createdAt)
            """
                .trimIndent()
        ) {
            execute()
            Unit
        }
    }

    logger.info { "Database migrations completed" }
}

private suspend fun SafeConnection.tableExists(tableName: String): Boolean {
    return query("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?") {
            setString(1, tableName)
            val rs = executeQuery()
            rs.next()
        }
        .let { exists -> exists }
}
