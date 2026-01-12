package com.alexn.socialpublish.server

import arrow.core.Either
import com.alexn.socialpublish.config.AppConfig
import com.alexn.socialpublish.db.DocumentsDatabase
import com.alexn.socialpublish.db.FilesDatabase
import com.alexn.socialpublish.db.PostsDatabase
import com.alexn.socialpublish.modules.AuthModule
import com.alexn.socialpublish.modules.BlueskyApiModule
import com.alexn.socialpublish.modules.FilesModule
import com.alexn.socialpublish.modules.FormModule
import com.alexn.socialpublish.modules.MastodonApiModule
import com.alexn.socialpublish.modules.RssModule
import com.alexn.socialpublish.modules.TwitterApiModule
import com.alexn.socialpublish.modules.configureAuth
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticFiles
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import org.slf4j.event.Level

private val logger = KotlinLogging.logger {}

suspend fun startServer(
    config: AppConfig,
    documentsDb: DocumentsDatabase,
    postsDb: PostsDatabase,
    filesDb: FilesDatabase,
) {
    logger.info { "Starting HTTP server on port ${config.httpPort}..." }

    val authModule = AuthModule(config)
    val rssModule = RssModule(config, postsDb, filesDb)
    val filesModule = FilesModule(config, filesDb)
    val blueskyModule = BlueskyApiModule(config, filesModule)
    val mastodonModule = MastodonApiModule(config, filesModule)
    val twitterModule = TwitterApiModule(config, documentsDb, filesModule)
    val formModule = FormModule(mastodonModule, blueskyModule, twitterModule, rssModule)

    embeddedServer(Netty, port = config.httpPort) {
        install(ContentNegotiation) {
            json(
                Json {
                    prettyPrint = true
                    isLenient = true
                    ignoreUnknownKeys = true
                },
            )
        }

        install(CallLogging) {
            level = Level.INFO
        }

        install(StatusPages) {
            exception<Throwable> { call, cause ->
                logger.error(cause) { "Unhandled exception" }
                call.respondText(
                    text = "500: ${cause.message}",
                    status = HttpStatusCode.InternalServerError,
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

                // Social media posts
                post("/api/bluesky/post") {
                    blueskyModule.createPostRoute(call)
                }

                post("/api/mastodon/post") {
                    mastodonModule.createPostRoute(call)
                }

                post("/api/twitter/post") {
                    twitterModule.createPostRoute(call)
                }

                // Twitter OAuth flow
                get("/api/twitter/authorize") {
                    val token = call.request.queryParameters["access_token"] ?: ""
                    twitterModule.authorizeRoute(call, token)
                }

                get("/api/twitter/status") {
                    twitterModule.statusRoute(call)
                }

                post("/api/multiple/post") {
                    formModule.broadcastPostRoute(call)
                }
            }

            // Twitter OAuth callback (public, no auth required)
            get("/api/twitter/callback") {
                twitterModule.callbackRoute(call)
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

            // Static file serving for frontend
            staticFiles("/", java.io.File("public"))

            // Frontend routing - serve index.html for SPA routes
            get("/{path...}") {
                val path = call.parameters.getAll("path")?.joinToString("/") ?: ""
                if (path.matches(Regex("^(login|form|account).*"))) {
                    call.respondFile(java.io.File("public/index.html"))
                } else {
                    call.respond(HttpStatusCode.NotFound)
                }
            }
        }
    }.start(wait = true)
}
