package socialpublish.backend.server

import arrow.continuations.ktor.server
import arrow.core.Either
import arrow.fx.coroutines.resource
import socialpublish.backend.AppConfig
import socialpublish.backend.db.DocumentsDatabase
import socialpublish.backend.db.FilesDatabase
import socialpublish.backend.db.PostsDatabase
import socialpublish.backend.integrations.bluesky.BlueskyApiModule
import socialpublish.backend.integrations.mastodon.MastodonApiModule
import socialpublish.backend.integrations.twitter.TwitterApiModule
import socialpublish.backend.models.NewBlueSkyPostResponse
import socialpublish.backend.models.NewMastodonPostResponse
import socialpublish.backend.models.NewPostResponse
import socialpublish.backend.models.NewRssPostResponse
import socialpublish.backend.models.NewTwitterPostResponse
import socialpublish.backend.modules.AuthModule
import socialpublish.backend.modules.FilesModule
import socialpublish.backend.modules.FormModule
import socialpublish.backend.modules.RssModule
import socialpublish.backend.modules.configureAuth
import socialpublish.backend.modules.extractJwtToken
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngineFactory
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
import java.io.File
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import org.slf4j.event.Level

private val logger = KotlinLogging.logger {}

fun startServer(
    config: AppConfig,
    documentsDb: DocumentsDatabase,
    postsDb: PostsDatabase,
    filesDb: FilesDatabase,
    engine: ApplicationEngineFactory<*, *> = CIO,
) = resource {
    logger.info { "Starting HTTP server on port ${config.server.httpPort}..." }

    val rssModule = RssModule(config.server.baseUrl, postsDb, filesDb)
    val filesModule = FilesModule.create(config.files, filesDb)

    val blueskyClient = config.bluesky?.let { BlueskyApiModule.defaultHttpClient() }
    val mastodonClient = config.mastodon?.let { MastodonApiModule.defaultHttpClient() }
    val twitterClient = config.twitter?.let { TwitterApiModule.defaultHttpClient() }

    // Conditionally instantiate integration modules based on config
    val blueskyModule = config.bluesky?.let { BlueskyApiModule(it, filesModule, blueskyClient!!) }
    val mastodonModule =
        config.mastodon?.let { MastodonApiModule(it, filesModule, mastodonClient!!) }
    val twitterModule =
        config.twitter?.let {
            TwitterApiModule(it, config.server.baseUrl, documentsDb, filesModule, twitterClient!!)
        }

    val authModule =
        AuthModule(
            config.server.auth,
            twitterAuthProvider = twitterModule?.let { { it.hasTwitterAuth() } },
        )

    val formModule = FormModule(mastodonModule, blueskyModule, twitterModule, rssModule)

    server(engine, port = config.server.httpPort) {
        install(CORS) {
            anyHost()
            allowHeader(io.ktor.http.HttpHeaders.ContentType)
            allowHeader(io.ktor.http.HttpHeaders.Authorization)
            allowCredentials = true
        }

        install(ContentNegotiation) {
            @OptIn(ExperimentalSerializationApi::class)
            json(
                Json {
                    prettyPrint = true
                    isLenient = true
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                    // Disable class discriminator to avoid adding "type" field
                    classDiscriminator = "#type"
                    classDiscriminatorMode = kotlinx.serialization.json.ClassDiscriminatorMode.NONE
                    serializersModule = SerializersModule {
                        polymorphic(NewPostResponse::class) {
                            subclass(NewRssPostResponse::class)
                            subclass(NewMastodonPostResponse::class)
                            subclass(NewBlueSkyPostResponse::class)
                            subclass(NewTwitterPostResponse::class)
                        }
                    }
                }
            )
        }

        install(CallLogging) { level = Level.INFO }

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
            get("/ping") { call.respondText("pong", status = HttpStatusCode.OK) }

            // Authentication routes
            post("/api/login") { authModule.login(call) }

            // Protected routes
            authenticate("auth-jwt") {
                get("/api/protected") { authModule.protectedRoute(call) }

                // RSS post creation
                post("/api/rss/post") { rssModule.createPostRoute(call) }

                // File upload
                post("/api/files/upload") {
                    val result = filesModule.uploadFile(call)
                    when (result) {
                        is Either.Right -> call.respond(result.value)
                        is Either.Left -> {
                            val error = result.value
                            call.respond(
                                HttpStatusCode.fromValue(error.status),
                                mapOf("error" to error.errorMessage),
                            )
                        }
                    }
                }

                // Social media posts
                post("/api/bluesky/post") {
                    if (blueskyModule != null) {
                        blueskyModule.createPostRoute(call)
                    } else {
                        call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            mapOf("error" to "Bluesky integration not configured"),
                        )
                    }
                }

                post("/api/mastodon/post") {
                    if (mastodonModule != null) {
                        mastodonModule.createPostRoute(call)
                    } else {
                        call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            mapOf("error" to "Mastodon integration not configured"),
                        )
                    }
                }

                post("/api/twitter/post") {
                    if (twitterModule != null) {
                        twitterModule.createPostRoute(call)
                    } else {
                        call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            mapOf("error" to "Twitter integration not configured"),
                        )
                    }
                }

                // Twitter OAuth flow
                get("/api/twitter/authorize") {
                    if (twitterModule != null) {
                        val token =
                            extractJwtToken(call)
                                ?: run {
                                    call.respond(
                                        HttpStatusCode.Unauthorized,
                                        mapOf("error" to "Unauthorized"),
                                    )
                                    return@get
                                }
                        twitterModule.authorizeRoute(call, token)
                    } else {
                        call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            mapOf("error" to "Twitter integration not configured"),
                        )
                    }
                }

                get("/api/twitter/callback") {
                    if (twitterModule != null) {
                        twitterModule.callbackRoute(call)
                    } else {
                        call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            mapOf("error" to "Twitter integration not configured"),
                        )
                    }
                }

                get("/api/twitter/status") {
                    if (twitterModule != null) {
                        twitterModule.statusRoute(call)
                    } else {
                        call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            mapOf("error" to "Twitter integration not configured"),
                        )
                    }
                }

                post("/api/multiple/post") { formModule.broadcastPostRoute(call) }
            }

            // Public RSS feed
            get("/rss") { rssModule.generateRssRoute(call) }

            get("/rss/target/{target}") { rssModule.generateRssRoute(call) }

            get("/rss/{uuid}") { rssModule.getRssItem(call) }

            get("/files/{uuid}") { filesModule.getFile(call) }

            // Manual static file serving with absolute paths and fallback
            if (config.server.staticContentPaths.isNotEmpty()) {
                get("/{path...}") {
                    val path = call.parameters.getAll("path")?.joinToString("/") ?: ""
                    val triedPaths = mutableListOf<String>()
                    for (baseDir in config.server.staticContentPaths) {
                        val canonicalBaseDir = baseDir.canonicalFile

                        val file =
                            if (path.isBlank() || path.matches(Regex("^(login|form|account).*"))) {
                                File(canonicalBaseDir, "index.html")
                            } else {
                                File(canonicalBaseDir, path)
                            }

                        if (
                            file.exists() &&
                                file.isFile &&
                                file.canonicalPath.startsWith(canonicalBaseDir.path)
                        ) {
                            call.respondFile(file)
                            return@get
                        }
                        triedPaths.add(file.canonicalPath)
                    }

                    logger.warn {
                        "Static file not found. Tried paths:\n${
                            triedPaths.joinToString(
                                ",\n"
                            )
                        }"
                    }
                    call.respond(HttpStatusCode.NotFound)
                }
            }
        }
    }
}
