package com.alexn.socialpublish.db

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import arrow.fx.coroutines.Resource
import arrow.fx.coroutines.resource
import com.alexn.socialpublish.models.ApiError
import com.alexn.socialpublish.models.CaughtException
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.KotlinPlugin
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * Database connection wrapper using Arrow's Resource for safe resource management
 */
object Database {
    fun resource(dbPath: String): Resource<Jdbi> =
        resource {
            logger.info { "Connecting to database at $dbPath" }

            // Ensure parent directory exists
            val dbFile = File(dbPath)
            dbFile.parentFile?.mkdirs()

            val jdbi =
                Jdbi.create("jdbc:sqlite:$dbPath")
                    .installPlugin(KotlinPlugin())

            // Run migrations
            jdbi.useHandle<Exception> { handle ->
                runMigrations(handle)
            }

            logger.info { "Database connected and migrated" }
            jdbi
        }

    private fun runMigrations(handle: Handle) {
        logger.info { "Running database migrations..." }

        // Documents table migration
        if (!tableExists(handle, "documents")) {
            logger.info { "Creating documents table" }
            handle.execute("DROP TABLE IF EXISTS posts")
            handle.execute(
                """
                CREATE TABLE IF NOT EXISTS documents (
                    uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                    search_key VARCHAR(255) UNIQUE NOT NULL,
                    kind VARCHAR(255) NOT NULL,
                    payload TEXT NOT NULL,
                    created_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            handle.execute(
                """
                CREATE INDEX IF NOT EXISTS documents_created_at
                ON documents(kind, created_at)
                """.trimIndent(),
            )
        }

        // Document tags table migration
        if (!tableExists(handle, "document_tags")) {
            logger.info { "Creating document_tags table" }
            handle.execute(
                """
                CREATE TABLE IF NOT EXISTS document_tags (
                   document_uuid VARCHAR(36) NOT NULL,
                   name VARCHAR(255) NOT NULL,
                   kind VARCHAR(255) NOT NULL,
                   PRIMARY KEY (document_uuid, name, kind)
                )
                """.trimIndent(),
            )
        }

        // Uploads table migration
        if (!tableExists(handle, "uploads")) {
            logger.info { "Creating uploads table" }
            handle.execute(
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
                """.trimIndent(),
            )
            handle.execute(
                """
                CREATE INDEX IF NOT EXISTS uploads_createdAt
                    ON uploads(createdAt)
                """.trimIndent(),
            )
        }

        logger.info { "Database migrations completed" }
    }

    private fun tableExists(
        handle: Handle,
        tableName: String,
    ): Boolean {
        return handle.createQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=:name",
        )
            .bind("name", tableName)
            .mapTo(Int::class.java)
            .findOne()
            .isPresent
    }
}

/**
 * Execute a database operation safely with typed errors
 */
suspend fun <T> safeDbOperation(block: suspend () -> T): Either<ApiError, T> {
    return try {
        block().right()
    } catch (e: Exception) {
        logger.error(e) { "Database operation failed" }
        CaughtException(
            status = 500,
            module = "database",
            errorMessage = "Database operation failed: ${e.message}",
        ).left()
    }
}
