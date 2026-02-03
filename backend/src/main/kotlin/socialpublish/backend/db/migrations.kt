package socialpublish.backend.db

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
        // Migration 5: Add settings column to users table
        Migration(
            ddl =
                listOf(
                    """
                    ALTER TABLE users ADD COLUMN settings TEXT
                    """
                ),
            testIfApplied = { conn -> conn.columnExists("users", "settings") },
        ),
        // Migration 6: Add user_uuid column to documents table
        Migration(
            ddl =
                listOf(
                    """
                    ALTER TABLE documents ADD COLUMN user_uuid VARCHAR(36)
                    """,
                    """
                    CREATE INDEX IF NOT EXISTS documents_user_uuid
                        ON documents(user_uuid, kind, created_at)
                    """,
                ),
            testIfApplied = { conn -> conn.columnExists("documents", "user_uuid") },
        ),
        // Migration 7: Add user_uuid column to document_tags table
        Migration(
            ddl =
                listOf(
                    """
                    ALTER TABLE document_tags ADD COLUMN user_uuid VARCHAR(36)
                    """
                ),
            testIfApplied = { conn -> conn.columnExists("document_tags", "user_uuid") },
        ),
        // Migration 8: Add user_uuid column to uploads table
        Migration(
            ddl =
                listOf(
                    """
                    ALTER TABLE uploads ADD COLUMN user_uuid VARCHAR(36)
                    """,
                    """
                    CREATE INDEX IF NOT EXISTS uploads_user_uuid
                        ON uploads(user_uuid, createdAt)
                    """,
                ),
            testIfApplied = { conn -> conn.columnExists("uploads", "user_uuid") },
        ),
        // Migration 9: Create admin user if no users exist, and assign all data to admin
        Migration(
            ddl =
                listOf(
                    """
                    INSERT INTO users (uuid, username, password_hash, settings, created_at, updated_at)
                    SELECT 
                        '00000000-0000-0000-0000-000000000001',
                        'admin',
                        '${'$'}2a${'$'}12${'$'}LQnzVXxbDhXe8z9F.gxXguLlKQqz8KL6P3fZH8tZ0qUZvH3YI5X.W',
                        NULL,
                        strftime('%s','now') * 1000,
                        strftime('%s','now') * 1000
                    WHERE NOT EXISTS (SELECT 1 FROM users)
                    """,
                    """
                    UPDATE documents 
                    SET user_uuid = '00000000-0000-0000-0000-000000000001'
                    WHERE user_uuid IS NULL
                    """,
                    """
                    UPDATE document_tags 
                    SET user_uuid = '00000000-0000-0000-0000-000000000001'
                    WHERE user_uuid IS NULL
                    """,
                    """
                    UPDATE uploads 
                    SET user_uuid = '00000000-0000-0000-0000-000000000001'
                    WHERE user_uuid IS NULL
                    """,
                ),
            testIfApplied = { conn ->
                conn.query(
                    "SELECT COUNT(*) as count FROM users WHERE uuid = '00000000-0000-0000-0000-000000000001'"
                ) {
                    val rs = executeQuery()
                    rs.next() && rs.getInt("count") > 0
                }
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
 * Helper function to check if a column exists in a table.
 *
 * @param tableName The name of the table
 * @param columnName The name of the column to check
 * @return true if the column exists, false otherwise
 */
private suspend fun SafeConnection.columnExists(tableName: String, columnName: String): Boolean =
    query("PRAGMA table_info($tableName)") {
        val rs = executeQuery().safe()
        rs.toList { it.getString("name") }.contains(columnName)
    }
