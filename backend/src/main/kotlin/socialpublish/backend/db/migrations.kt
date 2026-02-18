package socialpublish.backend.db

import at.favre.lib.crypto.bcrypt.BCrypt as FavreBCrypt
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Instant
import java.util.UUID
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid as KotlinUuid

private val logger = KotlinLogging.logger {}

@OptIn(ExperimentalUuidApi::class)
private fun generateUuidV7(): UUID = UUID.fromString(KotlinUuid.generateV7().toString())

/**
 * Represents a database migration with DDL statements and a test to check if it has been applied.
 *
 * This structure provides a clear, declarative way to define database schema changes, making
 * migrations easier to understand, test, and maintain.
 */
data class Migration(
    /** List of DDL statements to execute for this migration */
    val ddl: List<String>,
    /**
     * Test function to check if this migration has already been applied. Returns true if the
     * migration has been applied, false otherwise.
     */
    val testIfApplied: suspend (SafeConnection) -> Boolean,
)

/**
 * All database migrations in order of application.
 *
 * Each migration is self-contained and includes both the DDL statements and a test to verify if it
 * has already been applied to the database.
 */
val migrations: List<Migration> =
    listOf(
        // Migration 0: Documents table
        Migration(
            ddl =
                listOf(
                    "DROP TABLE IF EXISTS posts",
                    """
                    CREATE TABLE IF NOT EXISTS documents (
                        uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                        search_key VARCHAR(255) UNIQUE NOT NULL,
                        kind VARCHAR(255) NOT NULL,
                        payload TEXT NOT NULL,
                        created_at INTEGER NOT NULL
                    )
                    """,
                    """
                    CREATE INDEX IF NOT EXISTS documents_created_at
                    ON documents(kind, created_at)
                    """,
                ),
            testIfApplied = { conn -> conn.tableExists("documents") },
        ),
        // Migration 1: Document tags table
        Migration(
            ddl =
                listOf(
                    """
                    CREATE TABLE IF NOT EXISTS document_tags (
                        document_uuid VARCHAR(36) NOT NULL,
                        name VARCHAR(255) NOT NULL,
                        kind VARCHAR(255) NOT NULL,
                        PRIMARY KEY (document_uuid, name, kind)
                    )
                    """
                ),
            testIfApplied = { conn -> conn.tableExists("document_tags") },
        ),
        // Migration 2: Uploads table
        Migration(
            ddl =
                listOf(
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
                    """,
                    """
                    CREATE INDEX IF NOT EXISTS uploads_createdAt
                        ON uploads(createdAt)
                    """,
                ),
            testIfApplied = { conn -> conn.tableExists("uploads") },
        ),
        // Migration 3: Users table
        Migration(
            ddl =
                listOf(
                    """
                    CREATE TABLE IF NOT EXISTS users (
                        uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                        username VARCHAR(255) UNIQUE NOT NULL,
                        password_hash VARCHAR(255) NOT NULL,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """
                ),
            testIfApplied = { conn -> conn.tableExists("users") },
        ),
        // Migration 4: User sessions table
        Migration(
            ddl =
                listOf(
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
                    """,
                    """
                    CREATE INDEX IF NOT EXISTS user_sessions_expires_at
                        ON user_sessions(expires_at)
                    """,
                ),
            testIfApplied = { conn -> conn.tableExists("user_sessions") },
        ),
        // Migration 5: Create default admin user (if no users exist)
        Migration(
            ddl = emptyList(), // Applied via custom logic below
            testIfApplied = { conn ->
                // Migration is applied if at least one user exists
                conn.query("SELECT COUNT(*) FROM users") {
                    val rs = executeQuery()
                    rs.next() && rs.getInt(1) > 0
                }
            },
        ),
        // Migration 6: Add settings column to users table
        Migration(
            ddl = listOf("ALTER TABLE users ADD COLUMN settings TEXT"),
            testIfApplied = { conn -> conn.columnExists("users", "settings") },
        ),
        // Migration 7: Add user_uuid to documents, document_tags, uploads; update existing rows
        Migration(
            ddl =
                listOf(
                    "ALTER TABLE documents ADD COLUMN user_uuid VARCHAR(36)",
                    "ALTER TABLE uploads ADD COLUMN user_uuid VARCHAR(36)",
                    """
                    CREATE INDEX IF NOT EXISTS documents_user_uuid
                        ON documents(user_uuid, kind, created_at)
                    """,
                    """
                    CREATE INDEX IF NOT EXISTS uploads_user_uuid
                        ON uploads(user_uuid, createdAt)
                    """,
                ),
            testIfApplied = { conn -> conn.columnExists("documents", "user_uuid") },
        ),
    )

/**
 * Apply migration 5 (create admin user) and migration 7 (assign existing rows to admin user) within
 * a running transaction. Called after DDL-only migrations have been applied.
 */
suspend fun SafeConnection.applyDataMigrations() {
    // Migration 5: ensure at least one user (admin) exists
    val userCount =
        query("SELECT COUNT(*) FROM users") {
            val rs = executeQuery()
            if (rs.next()) rs.getInt(1) else 0
        }
    if (userCount == 0) {
        val uuid = generateUuidV7()
        val passwordHash =
            String(FavreBCrypt.withDefaults().hash(12, "changeme".toCharArray()), Charsets.UTF_8)
        val now = Instant.now().toEpochMilli()
        query(
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
        logger.info {
            "Created default admin user (uuid: $uuid). Default password is 'changeme' — change it in /account."
        }
    }

    // Migration 7 data: update existing documents/uploads to belong to the admin user
    val adminUuid =
        query("SELECT uuid FROM users WHERE username = 'admin' LIMIT 1") {
            val rs = executeQuery()
            if (rs.next()) rs.getString("uuid") else null
        }
    if (adminUuid != null) {
        query("UPDATE documents SET user_uuid = ? WHERE user_uuid IS NULL") {
            setString(1, adminUuid)
            val _ = executeUpdate()
        }
        query("UPDATE uploads SET user_uuid = ? WHERE user_uuid IS NULL") {
            setString(1, adminUuid)
            val _ = executeUpdate()
        }
    }
}

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
