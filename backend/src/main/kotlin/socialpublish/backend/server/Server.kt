package socialpublish.backend.server

import arrow.continuations.ktor.server
import arrow.core.Either
import arrow.fx.coroutines.resource
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.OpenApiDoc
import io.ktor.openapi.OpenApiInfo
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngineFactory
import io.ktor.server.http.HttpRequestLifecycle
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.openapi.hide
import io.ktor.server.routing.openapi.plus
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.routing.routingRoot
import io.ktor.utils.io.ExperimentalKtorApi
import java.io.File
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import org.slf4j.event.Level
import socialpublish.backend.AppConfig
import socialpublish.backend.clients.bluesky.BlueskyApiModule
import socialpublish.backend.clients.linkedin.LinkedInApiModule
import socialpublish.backend.clients.mastodon.MastodonApiModule
import socialpublish.backend.clients.twitter.TwitterApiModule
import socialpublish.backend.db.DocumentsDatabase
import socialpublish.backend.db.FilesDatabase
import socialpublish.backend.db.PostsDatabase
import socialpublish.backend.models.CompositeErrorResponse
import socialpublish.backend.models.CompositeErrorWithDetails
import socialpublish.backend.models.ErrorResponse
import socialpublish.backend.models.NewBlueSkyPostResponse
import socialpublish.backend.models.NewLinkedInPostResponse
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
import socialpublish.backend.utils.isPathWithinBase
import socialpublish.backend.utils.parseUrl

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

    val blueskyModule = config.bluesky?.let { BlueskyApiModule.resource(it, filesModule).bind() }
    val mastodonModule = config.mastodon?.let { MastodonApiModule.resource(it, filesModule).bind() }
    val twitterModule =
        config.twitter?.let {
            TwitterApiModule.resource(it, config.server.baseUrl, documentsDb, filesModule).bind()
        }
    val linkedInModule =
        config.linkedin?.let {
            LinkedInApiModule.resource(it, config.server.baseUrl, documentsDb, filesModule).bind()
        }

    val authModule =
        AuthModule(
            config.server.auth,
            twitterAuthProvider = twitterModule?.let { { it.hasTwitterAuth() } },
            linkedInAuthProvider = linkedInModule?.let { { it.hasLinkedInAuth() } },
        )

    val formModule =
        FormModule(mastodonModule, blueskyModule, twitterModule, linkedInModule, rssModule)

    server(engine, port = config.server.httpPort, preWait = 5.seconds) {
        install(CORS) {
            val parsedUrl = parseUrl(config.server.baseUrl)
            if (parsedUrl != null && !parsedUrl.isLocal()) {
                allowHost(parsedUrl.host)
                // Allow same origin (when frontend is served from same domain)
                allowSameOrigin = true
            }
            allowHeader(io.ktor.http.HttpHeaders.ContentType)
            allowHeader(io.ktor.http.HttpHeaders.Authorization)
            allowCredentials = true
        }

        // Install HttpRequestLifecycle plugin to cancel requests when clients disconnect
        install(HttpRequestLifecycle) { cancelCallOnClose = true }

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
                            subclass(NewLinkedInPostResponse::class)
                        }
                        // Explicitly register serializers
                        contextual(
                            CompositeErrorResponse::class,
                            CompositeErrorResponse.serializer(),
                        )
                        contextual(
                            CompositeErrorWithDetails::class,
                            CompositeErrorWithDetails.serializer(),
                        )
                    }
                }
            )
        }

        install(CallLogging) { level = Level.INFO }

        // Configure rate limiting for login endpoint
        install(RateLimit) {
            register(RateLimitName("login")) { rateLimiter(limit = 20, refillPeriod = 5.minutes) }
        }

        install(StatusPages) {
            exception<Throwable> { call, cause ->
                logger.error(cause) { "Unhandled exception" }
                // Don't expose internal error details to clients for security
                call.respondText(
                    text = "500: Internal Server Error",
                    status = HttpStatusCode.InternalServerError,
                )
            }
        }

        // Configure JWT authentication
        configureAuth(config.server.auth)

        routing {
            // OpenAPI spec endpoint - generates spec from annotated routes
            @OptIn(ExperimentalKtorApi::class)
            get("/openapi.json") {
                    val openApiDoc =
                        OpenApiDoc(
                            info =
                                OpenApiInfo(
                                    title = "Social Publish API",
                                    version = "1.0.0",
                                    description =
                                        "API for publishing posts to social media platforms",
                                )
                        ) + call.application.routingRoot.descendants()
                    call.respond<OpenApiDoc>(openApiDoc)
                }
                .hide()

            // Swagger UI for API documentation - points to dynamically generated spec
            swaggerUI(path = "swagger", swaggerFile = "openapi.json") { version = "5.10.5" }

            // Health check endpoints
            @OptIn(ExperimentalKtorApi::class)
            get("/ping") { call.respondText("pong", status = HttpStatusCode.OK) }
                .describe {
                    summary = "Health check"
                    description = "Returns 'pong' to indicate the server is running"
                    responses { HttpStatusCode.OK { description = "Server is healthy" } }
                }

            // Authentication routes
            @OptIn(ExperimentalKtorApi::class)
            rateLimit(RateLimitName("login")) {
                post("/api/login") { authModule.login(call) }
                    .describe {
                        summary = "User login"
                        description = "Authenticate user and get JWT token"
                        responses {
                            HttpStatusCode.OK {
                                description = "Successful login, returns JWT token"
                            }
                            HttpStatusCode.Unauthorized { description = "Invalid credentials" }
                        }
                    }
            }

            // Protected routes
            @OptIn(ExperimentalKtorApi::class)
            authenticate("auth-jwt") {
                get("/api/protected") { authModule.protectedRoute(call) }
                    .describe {
                        summary = "Protected route"
                        description = "Test endpoint requiring authentication"
                        responses {
                            HttpStatusCode.OK { description = "Authenticated successfully" }
                            HttpStatusCode.Unauthorized { description = "Not authenticated" }
                        }
                    }

                // RSS post creation
                post("/api/rss/post") { rssModule.createPostRoute(call) }
                    .describe {
                        summary = "Create RSS post"
                        description = "Create a new RSS feed post"
                        responses {
                            HttpStatusCode.OK { description = "Post created successfully" }
                            HttpStatusCode.Unauthorized { description = "Not authenticated" }
                        }
                    }

                // File upload
                post("/api/files/upload") {
                        when (val result = filesModule.uploadFile(call)) {
                            is Either.Right -> call.respond(result.value)
                            is Either.Left -> {
                                val error = result.value
                                call.respond(
                                    HttpStatusCode.fromValue(error.status),
                                    ErrorResponse(error = error.errorMessage),
                                )
                            }
                        }
                    }
                    .describe {
                        summary = "Upload file"
                        description = "Upload a file for use in posts"
                        responses {
                            HttpStatusCode.OK { description = "File uploaded successfully" }
                            HttpStatusCode.Unauthorized { description = "Not authenticated" }
                        }
                    }

                // Social media posts
                post("/api/bluesky/post") {
                        if (blueskyModule != null) {
                            blueskyModule.createPostRoute(call)
                        } else {
                            call.respond(
                                HttpStatusCode.ServiceUnavailable,
                                ErrorResponse(error = "Bluesky integration not configured"),
                            )
                        }
                    }
                    .describe {
                        summary = "Create Bluesky post"
                        description = "Publish a post to Bluesky"
                        responses {
                            HttpStatusCode.OK { description = "Post created successfully" }
                            HttpStatusCode.Unauthorized { description = "Not authenticated" }
                            HttpStatusCode.ServiceUnavailable {
                                description = "Bluesky integration not configured"
                            }
                        }
                    }

                post("/api/mastodon/post") {
                        if (mastodonModule != null) {
                            mastodonModule.createPostRoute(call)
                        } else {
                            call.respond(
                                HttpStatusCode.ServiceUnavailable,
                                ErrorResponse(error = "Mastodon integration not configured"),
                            )
                        }
                    }
                    .describe {
                        summary = "Create Mastodon post"
                        description = "Publish a post to Mastodon"
                        responses {
                            HttpStatusCode.OK { description = "Post created successfully" }
                            HttpStatusCode.Unauthorized { description = "Not authenticated" }
                            HttpStatusCode.ServiceUnavailable {
                                description = "Mastodon integration not configured"
                            }
                        }
                    }

                post("/api/twitter/post") {
                        if (twitterModule != null) {
                            twitterModule.createPostRoute(call)
                        } else {
                            call.respond(
                                HttpStatusCode.ServiceUnavailable,
                                ErrorResponse(error = "Twitter integration not configured"),
                            )
                        }
                    }
                    .describe {
                        summary = "Create Twitter post"
                        description = "Publish a post to Twitter/X"
                        responses {
                            HttpStatusCode.OK { description = "Post created successfully" }
                            HttpStatusCode.Unauthorized { description = "Not authenticated" }
                            HttpStatusCode.ServiceUnavailable {
                                description = "Twitter integration not configured"
                            }
                        }
                    }

                post("/api/linkedin/post") {
                        if (linkedInModule != null) {
                            linkedInModule.createPostRoute(call)
                        } else {
                            call.respond(
                                HttpStatusCode.ServiceUnavailable,
                                ErrorResponse(error = "LinkedIn integration not configured"),
                            )
                        }
                    }
                    .describe {
                        summary = "Create LinkedIn post"
                        description = "Publish a post to LinkedIn"
                        responses {
                            HttpStatusCode.OK { description = "Post created successfully" }
                            HttpStatusCode.Unauthorized { description = "Not authenticated" }
                            HttpStatusCode.ServiceUnavailable {
                                description = "LinkedIn integration not configured"
                            }
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
                                        ErrorResponse(error = "Unauthorized"),
                                    )
                                    return@get
                                }
                        twitterModule.authorizeRoute(call, token)
                    } else {
                        call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            ErrorResponse(error = "Twitter integration not configured"),
                        )
                    }
                }

                get("/api/twitter/callback") {
                    if (twitterModule != null) {
                        twitterModule.callbackRoute(call)
                    } else {
                        call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            ErrorResponse(error = "Twitter integration not configured"),
                        )
                    }
                }

                get("/api/twitter/status") {
                    if (twitterModule != null) {
                        twitterModule.statusRoute(call)
                    } else {
                        call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            ErrorResponse(error = "Twitter integration not configured"),
                        )
                    }
                }

                // LinkedIn OAuth flow
                get("/api/linkedin/authorize") {
                    if (linkedInModule != null) {
                        val token =
                            extractJwtToken(call)
                                ?: run {
                                    call.respond(
                                        HttpStatusCode.Unauthorized,
                                        ErrorResponse(error = "Unauthorized"),
                                    )
                                    return@get
                                }
                        linkedInModule.authorizeRoute(call, token)
                    } else {
                        call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            ErrorResponse(error = "LinkedIn integration not configured"),
                        )
                    }
                }

                get("/api/linkedin/callback") {
                    if (linkedInModule != null) {
                        linkedInModule.callbackRoute(call)
                    } else {
                        call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            ErrorResponse(error = "LinkedIn integration not configured"),
                        )
                    }
                }

                get("/api/linkedin/status") {
                    if (linkedInModule != null) {
                        linkedInModule.statusRoute(call)
                    } else {
                        call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            ErrorResponse(error = "LinkedIn integration not configured"),
                        )
                    }
                }

                post("/api/multiple/post") { formModule.broadcastPostRoute(call) }
                    .describe {
                        summary = "Broadcast post to multiple platforms"
                        description = "Publish a post to multiple social media platforms at once"
                        responses {
                            HttpStatusCode.OK { description = "Posts created successfully" }
                            HttpStatusCode.Unauthorized { description = "Not authenticated" }
                        }
                    }
            }

            // Public RSS feed
            @OptIn(ExperimentalKtorApi::class)
            get("/rss") { rssModule.generateRssRoute(call) }
                .describe {
                    summary = "Get RSS feed"
                    description = "Generate and retrieve the RSS feed"
                    responses { HttpStatusCode.OK { description = "RSS feed" } }
                }

            @OptIn(ExperimentalKtorApi::class)
            get("/rss/target/{target}") { rssModule.generateRssRoute(call) }
                .describe {
                    summary = "Get RSS feed for specific target"
                    description = "Generate and retrieve the RSS feed for a specific target"
                    responses { HttpStatusCode.OK { description = "RSS feed for target" } }
                }

            @OptIn(ExperimentalKtorApi::class)
            get("/rss/{uuid}") { rssModule.getRssItem(call) }
                .describe {
                    summary = "Get RSS item"
                    description = "Retrieve a specific RSS item by UUID"
                    responses {
                        HttpStatusCode.OK { description = "RSS item" }
                        HttpStatusCode.NotFound { description = "Item not found" }
                    }
                }

            @OptIn(ExperimentalKtorApi::class)
            get("/files/{uuid}") { filesModule.getFile(call) }
                .describe {
                    summary = "Get uploaded file"
                    description = "Retrieve an uploaded file by UUID"
                    responses {
                        HttpStatusCode.OK { description = "File content" }
                        HttpStatusCode.NotFound { description = "File not found" }
                    }
                }

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

                        // Security: Check that the resolved file is within the allowed directory
                        if (
                            file.exists() && file.isFile && isPathWithinBase(file, canonicalBaseDir)
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
