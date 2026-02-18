package socialpublish.backend.db

import at.favre.lib.crypto.bcrypt.BCrypt as FavreBCrypt
import io.github.oshai.kotlinlogging.KotlinLogging
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid as KotlinUuid

private val logger = KotlinLogging.logger {}

@OptIn(ExperimentalUuidApi::class)
private fun generateUuidV7(): UUID = UUID.fromString(KotlinUuid.generateV7().toString())

private fun generateRandomPassword(length: Int = 16): String {
    val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#\$%^&*"
    val rng = SecureRandom()
    return (1..length).map { chars[rng.nextInt(chars.length)] }.joinToString("")
}

/**
 * A database migration with an idempotency check and an execution function.
 *
 * [testIfApplied] returns true if this migration has already been applied and should be skipped.
 * [execute] performs the actual schema and/or data changes inside the current transaction.
 */
data class Migration(
    /**
     * Test function to check if this migration has already been applied. Returns true if the
     * migration has been applied, false otherwise.
     */
    val testIfApplied: suspend (SafeConnection) -> Boolean,
    /** Executes the migration DDL/DML — only called when [testIfApplied] returned false. */
    val execute: suspend (SafeConnection) -> Unit,
)

/** Convenience to execute a single DDL statement inside a migration. */
private suspend fun SafeConnection.ddl(sql: String) {
    query(sql) {
        execute()
        Unit
    }
}

/**
 * All database migrations in order.
 *
 * Each migration is self-contained and idempotent: [Migration.testIfApplied] determines whether
 * the migration needs to run, and [Migration.execute] performs the changes.
 */
val migrations: List<Migration> =
    listOf(
        // Migration 0: Documents table
        Migration(
            testIfApplied = { conn -> conn.tableExists("documents") },
            execute = { conn ->
                conn.ddl("DROP TABLE IF EXISTS posts")
                conn.ddl(
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
                conn.ddl(
                    """
                    CREATE INDEX IF NOT EXISTS documents_created_at
                    ON documents(kind, created_at)
                    """
                        .trimIndent()
                )
            },
        ),
        // Migration 1: Document tags table
        Migration(
            testIfApplied = { conn -> conn.tableExists("document_tags") },
            execute = { conn ->
                conn.ddl(
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
            },
        ),
        // Migration 2: Uploads table
        Migration(
            testIfApplied = { conn -> conn.tableExists("uploads") },
            execute = { conn ->
                conn.ddl(
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
                conn.ddl(
                    """
                    CREATE INDEX IF NOT EXISTS uploads_createdAt
                        ON uploads(createdAt)
                    """
                        .trimIndent()
                )
            },
        ),
        // Migration 3: Users table
        Migration(
            testIfApplied = { conn -> conn.tableExists("users") },
            execute = { conn ->
                conn.ddl(
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
            },
        ),
        // Migration 4: User sessions table
        Migration(
            testIfApplied = { conn -> conn.tableExists("user_sessions") },
            execute = { conn ->
                conn.ddl(
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
                conn.ddl(
                    """
                    CREATE INDEX IF NOT EXISTS user_sessions_expires_at
                        ON user_sessions(expires_at)
                    """
                        .trimIndent()
                )
            },
        ),
        // Migration 5: Create default admin user (only if no users exist)
        Migration(
            testIfApplied = { conn ->
                conn.query("SELECT COUNT(*) FROM users") {
                    val rs = executeQuery()
                    rs.next() && rs.getInt(1) > 0
                }
            },
            execute = { conn ->
                val uuid = generateUuidV7()
                val password = generateRandomPassword()
                val passwordHash =
                    String(
                        FavreBCrypt.withDefaults().hash(12, password.toCharArray()),
                        Charsets.UTF_8,
                    )
                val now = Instant.now().toEpochMilli()
                conn.query(
                    "INSERT INTO users (uuid, username, password_hash, created_at, updated_at) VALUES (?, ?, ?, ?, ?)"
                ) {
                    setString(1, uuid.toString())
                    setString(2, "admin")
                    setString(3, passwordHash)
                    setLong(4, now)
                    setLong(5, now)
                    execute()
                    Unit
                }
                logger.warn {
                    "Created default admin user (uuid: $uuid). " +
                        "Generated password: $password — change it immediately via the /account page or the change-password CLI command."
                }
            },
        ),
        // Migration 6: Add settings column to users table
        Migration(
            testIfApplied = { conn -> conn.columnExists("users", "settings") },
            execute = { conn -> conn.ddl("ALTER TABLE users ADD COLUMN settings TEXT") },
        ),
        // Migration 7: Add user_uuid (NOT NULL) to documents and uploads.
        // - Adds column (nullable) if absent, backfills rows to the single existing user,
        //   drops the old indexes without user_uuid, recreates tables with NOT NULL, then
        //   recreates the new indexes.
        // testIfApplied: true only when user_uuid is already NOT NULL in documents.
        Migration(
            testIfApplied = { conn ->
                conn.query(
                    "SELECT notnull FROM pragma_table_info('documents') WHERE name = 'user_uuid'"
                ) {
                    val rs = executeQuery()
                    rs.next() && rs.getInt(1) == 1
                }
            },
            execute = { conn ->
                // Step 1: add nullable column if it doesn't exist yet
                if (!conn.columnExists("documents", "user_uuid")) {
                    conn.ddl("ALTER TABLE documents ADD COLUMN user_uuid VARCHAR(36)")
                }
                if (!conn.columnExists("uploads", "user_uuid")) {
                    conn.ddl("ALTER TABLE uploads ADD COLUMN user_uuid VARCHAR(36)")
                }

                // Step 2: backfill — assign existing rows to the sole user in the DB.
                // At this point we know there is exactly one user (created by migration 5).
                val adminUuid =
                    conn.query("SELECT uuid FROM users LIMIT 1") {
                        val rs = executeQuery()
                        if (rs.next()) rs.getString("uuid") else null
                    }
                if (adminUuid != null) {
                    conn.query("UPDATE documents SET user_uuid = ? WHERE user_uuid IS NULL") {
                        setString(1, adminUuid)
                        val _ = executeUpdate()
                    }
                    conn.query("UPDATE uploads SET user_uuid = ? WHERE user_uuid IS NULL") {
                        setString(1, adminUuid)
                        val _ = executeUpdate()
                    }
                }

                // Step 3: drop old indexes (they lack user_uuid and are now suboptimal)
                conn.ddl("DROP INDEX IF EXISTS documents_created_at")
                conn.ddl("DROP INDEX IF EXISTS uploads_createdAt")
                // Also drop any previously created user_uuid indexes before recreation
                conn.ddl("DROP INDEX IF EXISTS documents_user_uuid")
                conn.ddl("DROP INDEX IF EXISTS uploads_user_uuid")

                // Step 4: recreate documents with user_uuid NOT NULL
                conn.ddl(
                    """
                    CREATE TABLE documents_new (
                        uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                        search_key VARCHAR(255) UNIQUE NOT NULL,
                        kind VARCHAR(255) NOT NULL,
                        payload TEXT NOT NULL,
                        user_uuid VARCHAR(36) NOT NULL,
                        created_at INTEGER NOT NULL
                    )
                    """
                        .trimIndent()
                )
                conn.ddl(
                    "INSERT INTO documents_new (uuid, search_key, kind, payload, user_uuid, created_at) " +
                        "SELECT uuid, search_key, kind, payload, user_uuid, created_at FROM documents"
                )
                conn.ddl("DROP TABLE documents")
                conn.ddl("ALTER TABLE documents_new RENAME TO documents")
                conn.ddl(
                    "CREATE INDEX documents_user_uuid ON documents(user_uuid, kind, created_at)"
                )

                // Step 5: recreate uploads with user_uuid NOT NULL
                conn.ddl(
                    """
                    CREATE TABLE uploads_new (
                        uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                        hash VARCHAR(64) NOT NULL,
                        originalname VARCHAR(255) NOT NULL,
                        mimetype VARCHAR(255),
                        size INTEGER,
                        altText TEXT,
                        imageWidth INTEGER,
                        imageHeight INTEGER,
                        user_uuid VARCHAR(36) NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """
                        .trimIndent()
                )
                conn.ddl(
                    "INSERT INTO uploads_new (uuid, hash, originalname, mimetype, size, altText, imageWidth, imageHeight, user_uuid, createdAt) " +
                        "SELECT uuid, hash, originalname, mimetype, size, altText, imageWidth, imageHeight, user_uuid, createdAt FROM uploads"
                )
                conn.ddl("DROP TABLE uploads")
                conn.ddl("ALTER TABLE uploads_new RENAME TO uploads")
                conn.ddl("CREATE INDEX uploads_user_uuid ON uploads(user_uuid, createdAt)")
            },
        ),
    )

/**
 * Helper function to check if a table exists in the SQLite database.
 *
 * @param tableName The name of the table to check
 * @return true if the table exists, false otherwise
 */
private suspend fun SafeConnection.tableExists(tableName: String): Boolean =
    query("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?") {
        setString(1, tableName)
        val rs = executeQuery()
        rs.next()
    }

/**
 * Helper function to check if a column exists in a table in the SQLite database.
 *
 * @param tableName The name of the table
 * @param columnName The name of the column
 * @return true if the column exists, false otherwise
 */
private suspend fun SafeConnection.columnExists(tableName: String, columnName: String): Boolean =
    query("PRAGMA table_info($tableName)") {
        val rs = executeQuery()
        rs.safe().toList { it.getString("name") }.contains(columnName)
    }
