package com.alexn.socialpublish

import com.alexn.socialpublish.config.AppCliCommand
import com.alexn.socialpublish.db.DocumentsDatabase
import com.alexn.socialpublish.db.FilesDatabase
import com.alexn.socialpublish.db.PostsDatabase
import com.alexn.socialpublish.server.startServer
import com.github.ajalt.clikt.core.main
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.KotlinPlugin
import java.io.File

private val logger = KotlinLogging.logger {}

fun main(args: Array<String>) = runBlocking {
    logger.info { "Starting Social Publish application..." }
    
    val cliCommand = AppCliCommand()
    cliCommand.main(args)
    val config = cliCommand.config
    
    try {
        // Initialize database
        val dbFile = File(config.dbPath)
        dbFile.parentFile?.mkdirs()
        
        val jdbi = Jdbi.create("jdbc:sqlite:${config.dbPath}")
            .installPlugin(KotlinPlugin())
        
        // Run migrations
        jdbi.useHandle<Exception> { handle ->
            runMigrations(handle)
        }
        
        // Initialize database repositories
        val documentsDb = DocumentsDatabase(jdbi)
        val postsDb = PostsDatabase(documentsDb)
        val filesDb = FilesDatabase(jdbi)
        
        logger.info { "Database initialized successfully" }
        
        // Start HTTP server
        startServer(config, documentsDb, postsDb, filesDb)
    } catch (e: Exception) {
        logger.error(e) { "Application failed to start" }
        throw e
    }
}

private fun runMigrations(handle: org.jdbi.v3.core.Handle) {
    logger.info { "Running database migrations..." }
    
    fun tableExists(tableName: String): Boolean {
        return handle.createQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=:name"
        )
            .bind("name", tableName)
            .mapTo(Int::class.java)
            .findOne()
            .isPresent
    }
    
    // Documents table migration
    if (!tableExists("documents")) {
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
            """.trimIndent()
        )
        handle.execute(
            """
            CREATE INDEX IF NOT EXISTS documents_created_at
            ON documents(kind, created_at)
            """.trimIndent()
        )
    }
    
    // Document tags table migration
    if (!tableExists("document_tags")) {
        logger.info { "Creating document_tags table" }
        handle.execute(
            """
            CREATE TABLE IF NOT EXISTS document_tags (
               document_uuid VARCHAR(36) NOT NULL,
               name VARCHAR(255) NOT NULL,
               kind VARCHAR(255) NOT NULL,
               PRIMARY KEY (document_uuid, name, kind)
            )
            """.trimIndent()
        )
    }
    
    // Uploads table migration
    if (!tableExists("uploads")) {
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
            """.trimIndent()
        )
        handle.execute(
            """
            CREATE INDEX IF NOT EXISTS uploads_createdAt
                ON uploads(createdAt)
            """.trimIndent()
        )
    }
    
    logger.info { "Database migrations completed" }
}
