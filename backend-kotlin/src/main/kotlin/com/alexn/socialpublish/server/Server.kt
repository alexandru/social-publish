package com.alexn.socialpublish.server

import arrow.core.Either
import com.alexn.socialpublish.AppConfig
import com.alexn.socialpublish.db.DocumentsDatabase
import com.alexn.socialpublish.db.FilesDatabase
import com.alexn.socialpublish.db.PostsDatabase
import com.alexn.socialpublish.integrations.bluesky.BlueskyApiModule
import com.alexn.socialpublish.integrations.mastodon.MastodonApiModule
import com.alexn.socialpublish.integrations.twitter.TwitterApiModule
import com.alexn.socialpublish.modules.AuthModule
import com.alexn.socialpublish.modules.FilesModule
import com.alexn.socialpublish.modules.FormModule
import com.alexn.socialpublish.modules.RssModule
import com.alexn.socialpublish.modules.configureAuth
import com.alexn.socialpublish.modules.extractJwtToken
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticFiles
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
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
): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
    logger.info { "Starting HTTP server on port ${config.server.httpPort}..." }

    val rssModule = RssModule(config.server.baseUrl, postsDb, filesDb)
    val filesModule = FilesModule.create(config.files, filesDb)

    val blueskyClient = config.bluesky?.let { BlueskyApiModule.defaultHttpClient() }
    val mastodonClient = config.mastodon?.let { MastodonApiModule.defaultHttpClient() }
    val twitterClient = config.twitter?.let { TwitterApiModule.defaultHttpClient() }

    // Conditionally instantiate integration modules based on config
    val blueskyModule = config.bluesky?.let { BlueskyApiModule(it, filesModule, blueskyClient!!) }
    val mastodonModule = config.mastodon?.let { MastodonApiModule(it, filesModule, mastodonClient!!) }
    val twitterModule =
        config.twitter?.let {
            TwitterApiModule(
                it,
                config.server.baseUrl,
                documentsDb,
                filesModule,
                twitterClient!!,
            )
        }

    val authModule =
        AuthModule(
            config.server.auth,
            twitterAuthProvider = twitterModule?.let { { it.hasTwitterAuth() } },
        )

    val formModule = FormModule(mastodonModule, blueskyModule, twitterModule, rssModule)

    return embeddedServer(Netty, port = config.server.httpPort) {
        install(CORS) {
            anyHost()
            allowHeader(io.ktor.http.HttpHeaders.ContentType)
            allowHeader(io.ktor.http.HttpHeaders.Authorization)
            allowCredentials = true
        }

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

        monitor.subscribe(io.ktor.server.application.ApplicationStopped) {
            blueskyClient?.close()
            mastodonClient?.close()
            twitterClient?.close()
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
        configureAuth(config.server.auth)

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
                    if (blueskyModule != null) {
                        blueskyModule.createPostRoute(call)
                    } else {
                        call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Bluesky integration not configured"))
                    }
                }

                post("/api/mastodon/post") {
                    if (mastodonModule != null) {
                        mastodonModule.createPostRoute(call)
                    } else {
                        call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Mastodon integration not configured"))
                    }
                }

                post("/api/twitter/post") {
                    if (twitterModule != null) {
                        twitterModule.createPostRoute(call)
                    } else {
                        call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Twitter integration not configured"))
                    }
                }

                // Twitter OAuth flow
                get("/api/twitter/authorize") {
                    if (twitterModule != null) {
                        val token =
                            extractJwtToken(call)
                                ?: run {
                                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
                                    return@get
                                }
                        twitterModule.authorizeRoute(call, token)
                    } else {
                        call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Twitter integration not configured"))
                    }
                }

                get("/api/twitter/callback") {
                    if (twitterModule != null) {
                        twitterModule.callbackRoute(call)
                    } else {
                        call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Twitter integration not configured"))
                    }
                }

                get("/api/twitter/status") {
                    if (twitterModule != null) {
                        twitterModule.statusRoute(call)
                    } else {
                        call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Twitter integration not configured"))
                    }
                }

                post("/api/multiple/post") {
                    formModule.broadcastPostRoute(call)
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
    }
}
