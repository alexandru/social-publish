package com.alexn.socialpublish.server

import arrow.core.Either
import com.alexn.socialpublish.config.AppConfig
import com.alexn.socialpublish.db.DocumentsDatabase
import com.alexn.socialpublish.db.FilesDatabase
import com.alexn.socialpublish.db.PostsDatabase
import com.alexn.socialpublish.modules.AuthModule
import com.alexn.socialpublish.modules.FilesModule
import com.alexn.socialpublish.modules.RssModule
import com.alexn.socialpublish.modules.configureAuth
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
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
    
    val authModule = AuthModule(config)
    val rssModule = RssModule(config, postsDb, filesDb)
    val filesModule = FilesModule(config, filesDb)
    
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
        
        // Configure JWT authentication
        configureAuth(config)
        
        routing {
            // Health check endpoints
            get("/ping") {
                call.respondText("pong", status = HttpStatusCode.OK)
            }
            
            // Authentication routes
            post("/api/login") {
                authModule.login(call)
            }
            
            // Protected routes
            authenticate("auth-jwt") {
                get("/api/protected") {
                    authModule.protectedRoute(call)
                }
                
                // RSS post creation
                post("/api/rss/post") {
                    rssModule.createPostRoute(call)
                }
                
                // File upload
                post("/api/files/upload") {
                    val result = filesModule.uploadFile(call)
                    when (result) {
                        is Either.Right -> call.respond(result.value)
                        is Either.Left -> {
                            val error = result.value
                            call.respond(HttpStatusCode.fromValue(error.status), mapOf("error" to error.errorMessage))
                        }
                    }
                }
                
                // Placeholder routes - to be implemented
                post("/api/bluesky/post") {
                    call.respondText("Bluesky post - not yet implemented", status = HttpStatusCode.NotImplemented)
                }
                
                post("/api/mastodon/post") {
                    call.respondText("Mastodon post - not yet implemented", status = HttpStatusCode.NotImplemented)
                }
                
                post("/api/multiple/post") {
                    call.respondText("Multiple post - not yet implemented", status = HttpStatusCode.NotImplemented)
                }
            }
            
            // Public RSS feed
            get("/rss") {
                rssModule.generateRssRoute(call)
            }
            
            get("/rss/target/{target}") {
                rssModule.generateRssRoute(call)
            }
            
            get("/rss/{uuid}") {
                rssModule.getRssItem(call)
            }
            
            get("/files/{uuid}") {
                filesModule.getFile(call)
            }
        }
    }.start(wait = true)
}
