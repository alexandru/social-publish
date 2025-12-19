package com.alexn.socialpublish.server

import com.alexn.socialpublish.config.AppConfig
import com.alexn.socialpublish.db.DocumentsDatabase
import com.alexn.socialpublish.db.FilesDatabase
import com.alexn.socialpublish.db.PostsDatabase
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import org.slf4j.event.Level

private val logger = KotlinLogging.logger {}

suspend fun startServer(
    config: AppConfig,
    documentsDb: DocumentsDatabase,
    postsDb: PostsDatabase,
    filesDb: FilesDatabase
) {
    logger.info { "Starting HTTP server on port ${config.httpPort}..." }
    
    embeddedServer(Netty, port = config.httpPort) {
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                isLenient = true
                ignoreUnknownKeys = true
            })
        }
        
        install(CallLogging) {
            level = Level.INFO
        }
        
        install(StatusPages) {
            exception<Throwable> { call, cause ->
                logger.error(cause) { "Unhandled exception" }
                call.respondText(
                    text = "500: ${cause.message}",
                    status = HttpStatusCode.InternalServerError
                )
            }
        }
        
        routing {
            // Health check endpoints
            get("/ping") {
                call.respondText("pong", status = HttpStatusCode.OK)
            }
            
            get("/api/protected") {
                call.respondText("Protected endpoint - authentication not yet implemented")
            }
            
            // Placeholder routes - to be implemented
            get("/rss") {
                call.respondText("RSS feed - not yet implemented", status = HttpStatusCode.NotImplemented)
            }
            
            post("/api/login") {
                call.respondText("Login - not yet implemented", status = HttpStatusCode.NotImplemented)
            }
        }
    }.start(wait = true)
}
