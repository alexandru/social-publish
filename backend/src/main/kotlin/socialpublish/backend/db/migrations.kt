package socialpublish.backend.db

import arrow.core.raise.context.Raise
import io.github.oshai.kotlinlogging.KotlinLogging
import java.sql.Types
import java.time.Instant

/**
 * A database migration with an idempotency check and an execution function.
 *
 * [testIfApplied] returns true if this migration has already been applied and
 * should be skipped. [execute] performs the actual schema and/or data changes
 * inside the current transaction.
 */
data class Migration(
    /**
     * Test function to check if this migration has already been applied.
     * Returns true if the migration has been applied, false otherwise.
     */
    val testIfApplied:
        suspend context(Raise<DBException>)
        (SafeConnection) -> Boolean,
    /**
     * Executes the migration DDL/DML — only called when [testIfApplied]
     * returned false.
     */
    val execute:
        suspend context(Raise<DBException>)
        (SafeConnection) -> Unit,
)

/** Convenience to execute a single DDL statement inside a migration. */
context(_: Raise<DBException>)
private suspend fun SafeConnection.ddl(vararg statements: String) {
    for (s in statements) {
        query(s.trimIndent()) {
            execute()
            Unit
        }
    }
}

/**
 * All database migrations in order.
 *
 * Each migration is self-contained and idempotent: [Migration.testIfApplied]
 * determines whether the migration needs to run, and [Migration.execute]
 * performs the changes.
 */
val migrations: List<Migration> =
    listOf(
        // Migration 0: Documents table
        Migration(
            testIfApplied = { conn -> conn.tableExists("documents") },
            execute = { conn ->
                conn.ddl(
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
                    """,
                    """
                    CREATE INDEX IF NOT EXISTS uploads_createdAt
                        ON uploads(createdAt)
                    """,
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
                    """,
                    """
                    CREATE INDEX IF NOT EXISTS user_sessions_expires_at
                        ON user_sessions(expires_at)
                    """,
                )
            },
        ),
        // Migration 5: Add settings column to users table
        Migration(
            testIfApplied = { conn -> conn.columnExists("users", "settings") },
            execute = { conn ->
                conn.ddl("ALTER TABLE users ADD COLUMN settings TEXT")
            },
        ),
        // Migration 6: Make users.password_hash nullable.
        Migration(
            testIfApplied = { conn ->
                conn.query("PRAGMA table_info('users')") {
                    val rs = executeQuery()
                    var nullable = false
                    while (rs.next()) {
                        if (
                            rs.getString("name") == "password_hash" &&
                                rs.getInt("notnull") == 0
                        ) {
                            nullable = true
                            break
                        }
                    }
                    nullable
                }
            },
            execute = { conn ->
                conn.ddl(
                    """
                    CREATE TABLE users_new (
                        uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                        username VARCHAR(255) UNIQUE NOT NULL,
                        password_hash VARCHAR(255),
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        settings TEXT
                    )
                    """,
                    """
                    INSERT INTO users_new
                        (uuid, username, password_hash, created_at, updated_at, settings)
                    SELECT
                        uuid, username, password_hash, created_at, updated_at, settings
                    FROM users
                    """,
                    "ALTER TABLE users RENAME TO users_old",
                    "ALTER TABLE users_new RENAME TO users",
                    "DROP TABLE users_old",
                )
            },
        ),
        // Migration 7: Create default admin user (only if no users exist)
        Migration(
            testIfApplied = { conn ->
                conn.query("SELECT COUNT(*) FROM users") {
                    val rs = executeQuery()
                    rs.next() && rs.getInt(1) > 0
                }
            },
            execute = { conn ->
                val uuid = UUIDv7.generate()
                val now = Instant.now().toEpochMilli()
                conn.query(
                    """
                    INSERT INTO users
                        (uuid, username, password_hash, created_at, updated_at)
                    VALUES
                        (?, ?, ?, ?, ?)
                    """
                ) {
                    setString(1, uuid.toString())
                    setString(2, "admin")
                    setNull(3, Types.VARCHAR)
                    setLong(4, now)
                    setLong(5, now)
                    execute()
                    Unit
                }
                logger.info {
                    "Created default admin user (uuid: $uuid) with password authentication disabled. " +
                        "Set a password via the change-password CLI command."
                }
            },
        ),
        // Migration 8: Add user_uuid (NOT NULL) to documents and uploads.
        // - Adds column (nullable) if absent, backfills rows to the single
        // existing user,
        //   drops the old indexes without user_uuid, recreates tables with NOT
        // NULL, then
        //   recreates the new indexes.
        // testIfApplied: true only when user_uuid is already NOT NULL in
        // documents.
        Migration(
            testIfApplied = { conn ->
                // Check that user_uuid exists AND is NOT NULL in documents.
                // We iterate PRAGMA results to avoid quoting issues with the
                // 'notnull' column name.
                conn.query("PRAGMA table_info('documents')") {
                    val rs = executeQuery()
                    var isNotNull = false
                    while (rs.next()) {
                        if (
                            rs.getString("name") == "user_uuid" &&
                                rs.getInt("notnull") == 1
                        ) {
                            isNotNull = true
                            break
                        }
                    }
                    isNotNull
                }
            },
            execute = { conn ->
                // Step 1: add nullable column if it doesn't exist yet
                if (!conn.columnExists("documents", "user_uuid")) {
                    conn.ddl(
                        "ALTER TABLE documents ADD COLUMN user_uuid VARCHAR(36)"
                    )
                }
                if (!conn.columnExists("uploads", "user_uuid")) {
                    conn.ddl(
                        "ALTER TABLE uploads ADD COLUMN user_uuid VARCHAR(36)"
                    )
                }

                // Step 2: backfill — assign existing rows to the sole user in
                // the DB.
                // At this point we know there is exactly one user (created by
                // migration 7).
                val adminUuid =
                    conn.query("SELECT uuid FROM users LIMIT 1") {
                        val rs = executeQuery()
                        if (rs.next()) rs.getString("uuid") else null
                    }
                if (adminUuid != null) {
                    conn.query(
                        "UPDATE documents SET user_uuid = ? WHERE user_uuid IS NULL"
                    ) {
                        setString(1, adminUuid)
                        val _ = executeUpdate()
                    }
                    conn.query(
                        "UPDATE uploads SET user_uuid = ? WHERE user_uuid IS NULL"
                    ) {
                        setString(1, adminUuid)
                        val _ = executeUpdate()
                    }
                }

                // Step 3: drop old indexes (they lack user_uuid and are now
                // suboptimal)
                conn.ddl(
                    "DROP INDEX IF EXISTS documents_created_at",
                    "DROP INDEX IF EXISTS uploads_createdAt",
                    // Also drop any previously created user_uuid indexes before
                    // recreation
                    "DROP INDEX IF EXISTS documents_user_uuid",
                    "DROP INDEX IF EXISTS uploads_user_uuid",
                )

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
                    """,
                    """
                    INSERT INTO documents_new
                        (uuid, search_key, kind, payload, user_uuid, created_at)
                    SELECT
                        uuid, search_key, kind, payload, user_uuid, created_at
                    FROM documents
                    """,
                    "ALTER TABLE documents RENAME TO documents_old",
                    "ALTER TABLE documents_new RENAME TO documents",
                    "DROP TABLE documents_old",
                    """
                    CREATE INDEX documents_user_uuid
                        ON documents(user_uuid, kind, created_at)
                    """,
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
                    """,
                    """
                    INSERT INTO uploads_new
                        (uuid, hash, originalname, mimetype, size, altText, imageWidth, imageHeight, user_uuid, createdAt)
                    SELECT
                        uuid, hash, originalname, mimetype, size, altText, imageWidth, imageHeight, user_uuid, createdAt
                    FROM uploads
                    """,
                    "ALTER TABLE uploads RENAME TO uploads_old",
                    "ALTER TABLE uploads_new RENAME TO uploads",
                    "DROP TABLE uploads_old",
                    // Create index on the renamed table, after old indexes are
                    // gone
                    """
                    CREATE INDEX uploads_user_uuid
                        ON uploads(user_uuid, createdAt)
                    """,
                )
            },
        ),
        // Migration 9: Remove refresh-token storage from user_sessions and add
        // revocation time.
        Migration(
            testIfApplied = { conn ->
                conn.columnExists("user_sessions", "revoked_at") &&
                    !conn.columnExists("user_sessions", "refresh_token_hash")
            },
            execute = { conn ->
                conn.ddl(
                    """
                    CREATE TABLE user_sessions_new (
                        uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                        user_uuid VARCHAR(36) NOT NULL,
                        token_hash VARCHAR(255) UNIQUE NOT NULL,
                        expires_at INTEGER NOT NULL,
                        created_at INTEGER NOT NULL,
                        revoked_at INTEGER,
                        FOREIGN KEY (user_uuid) REFERENCES users(uuid) ON DELETE CASCADE
                    )
                    """,
                    """
                    INSERT INTO user_sessions_new
                        (uuid, user_uuid, token_hash, expires_at, created_at, revoked_at)
                    SELECT
                        uuid, user_uuid, token_hash, expires_at, created_at, NULL
                    FROM user_sessions
                    """,
                    "ALTER TABLE user_sessions RENAME TO user_sessions_old",
                    "ALTER TABLE user_sessions_new RENAME TO user_sessions",
                    // Dropping the old table removes its old indexes before
                    // recreating current ones
                    "DROP TABLE user_sessions_old",
                    """
                    CREATE INDEX IF NOT EXISTS user_sessions_expires_at
                        ON user_sessions(expires_at)
                    """,
                    """
                    CREATE INDEX IF NOT EXISTS user_sessions_revoked_at
                        ON user_sessions(revoked_at)
                    """,
                )
            },
        ),
    )

/**
 * Helper function to check if a table exists in the SQLite database.
 *
 * @param tableName The name of the table to check
 * @return true if the table exists, false otherwise
 */
context(_: Raise<DBException>)
private suspend fun SafeConnection.tableExists(tableName: String): Boolean =
    query("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?") {
        setString(1, tableName)
        val rs = executeQuery()
        rs.next()
    }

/**
 * Helper function to check if a column exists in a table in the SQLite
 * database.
 *
 * @param tableName The name of the table
 * @param columnName The name of the column
 * @return true if the column exists, false otherwise
 */
context(_: Raise<DBException>)
private suspend fun SafeConnection.columnExists(
    tableName: String,
    columnName: String,
): Boolean =
    query("PRAGMA table_info($tableName)") {
        val rs = executeQuery()
        rs.safe().toList { it.getString("name") }.contains(columnName)
    }

private val logger = KotlinLogging.logger {}
