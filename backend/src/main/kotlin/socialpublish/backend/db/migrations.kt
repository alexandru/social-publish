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
                    """
                        .trimIndent(),
                    """
                    CREATE INDEX IF NOT EXISTS documents_created_at
                    ON documents(kind, created_at)
                    """
                        .trimIndent(),
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
                        .trimIndent()
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
                    """
                        .trimIndent(),
                    """
                    CREATE INDEX IF NOT EXISTS uploads_createdAt
                        ON uploads(createdAt)
                    """
                        .trimIndent(),
                ),
            testIfApplied = { conn -> conn.tableExists("uploads") },
        ),
    )

/**
 * Helper function to check if a table exists in the SQLite database.
 *
 * @param tableName The name of the table to check
 * @return true if the table exists, false otherwise
 */
private suspend fun SafeConnection.tableExists(tableName: String): Boolean {
    return query("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?") {
            setString(1, tableName)
            val rs = executeQuery()
            rs.next()
        }
        .let { exists -> exists }
}
