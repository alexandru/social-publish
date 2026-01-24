@file:OptIn(ExperimentalKtorApi::class)

package socialpublish.backend.server

import arrow.continuations.ktor.server
import arrow.core.Either
import arrow.fx.coroutines.resource
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.CachingOptions
import io.ktor.openapi.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngineFactory
import io.ktor.server.plugins.cachingheaders.CachingHeaders
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.ExperimentalKtorApi
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
import socialpublish.backend.db.Post
import socialpublish.backend.db.PostsDatabase
import socialpublish.backend.models.*
import socialpublish.backend.modules.*
import socialpublish.backend.utils.*

private val logger = KotlinLogging.logger {}

fun startServer(
    config: AppConfig,
    documentsDb: DocumentsDatabase,
    postsDb: PostsDatabase,
    filesDb: FilesDatabase,
    engine: ApplicationEngineFactory<*, *> = CIO,
) = resource {
    logger.info { "Starting HTTP server on port ${config.server.httpPort}..." }

    val staticFilesModule = StaticFilesModule(config.server)
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

        install(CachingHeaders) {
            options { call, _ ->
                val uri = call.request.local.uri
                // Only handle dynamic routes; static files are handled in StaticFilesModule
                when {
                    // No caching for dynamic routes
                    uri.startsWith("/api/") ||
                        uri.startsWith("/rss") ||
                        uri.startsWith("/files/") -> {
                        CachingOptions(cacheControl = CacheControl.NoCache(null))
                    }
                    else -> null
                }
            }
        }

        // Configure JWT authentication
        configureAuth(config.server.auth)
        // Configure OpenAPI / Swagger documentation
        configureOpenApiSecuritySchemes()

        routing {
            // Swagger UI for API documentation - points to dynamically generated spec
            swaggerUI(path = "docs") { info = OpenApiInfo("Social Publish API", "1.0.0") }

            // Health check endpoints
            get("/ping") { call.respondText("pong", status = HttpStatusCode.OK) }
                .describe {
                    summary = "Health check"
                    description = "Returns 'pong' to indicate the server is running"
                    responses {
                        HttpStatusCode.OK {
                            description = "Server is healthy"
                            ContentType.Text.Plain()
                        }
                    }
                }

            // Authentication routes
            rateLimit(RateLimitName("login")) {
                post("/api/login") { authModule.login(call) }
                    .describe {
                        summary = "User login"
                        description = "Authenticate user and get JWT token"
                        requestBody {
                            required = true
                            ContentType.Application.Json { schema = jsonSchema<LoginRequest>() }
                            ContentType.Application.FormUrlEncoded {
                                schema = jsonSchema<LoginRequest>()
                            }
                        }
                        responses {
                            HttpStatusCode.OK {
                                description = "Successful login, returns JWT token"
                                schema = jsonSchema<LoginResponse>()
                            }
                            HttpStatusCode.BadRequest {
                                description = "Missing username or password"
                                schema = jsonSchema<ErrorResponse>()
                            }
                            HttpStatusCode.Unauthorized {
                                description = "Invalid credentials"
                                schema = jsonSchema<ErrorResponse>()
                            }
                        }
                    }
            }

            // Protected routes
            authenticate("auth-jwt") {
                get("/api/protected") { authModule.protectedRoute(call) }
                    .describe {
                        summary = "Protected route"
                        description = "Test endpoint requiring authentication"
                        documentSecurityRequirements()
                        responses {
                            HttpStatusCode.OK {
                                description = "Authenticated successfully"
                                schema = jsonSchema<UserResponse>()
                            }
                            HttpStatusCode.Unauthorized {
                                description = "Not authenticated"
                                schema = jsonSchema<ErrorResponse>()
                            }
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
                        documentSecurityRequirements()
                        requestBody {
                            required = true

                            ContentType.MultiPart.FormData {
                                schema =
                                    JsonSchema(
                                        type = JsonType.OBJECT,
                                        required = listOf("file"),
                                        properties =
                                            mapOf(
                                                "altText" to
                                                    ReferenceOr.Value(
                                                        JsonSchema(
                                                            type = JsonType.STRING,
                                                            description =
                                                                "Alt text for accessibility",
                                                        )
                                                    ),
                                                "file" to
                                                    ReferenceOr.Value(
                                                        JsonSchema(
                                                            type = JsonType.STRING,
                                                            format = "binary", // <- file upload in
                                                            // OpenAPI
                                                            description = "Image file contents",
                                                        )
                                                    ),
                                            ),
                                    )
                            }
                        }
                        responses {
                            HttpStatusCode.OK {
                                description = "File uploaded successfully"
                                schema = jsonSchema<FileUploadResponse>()
                            }
                            HttpStatusCode.Unauthorized {
                                description = "Not authenticated"
                                schema = jsonSchema<ErrorResponse>()
                            }
                            HttpStatusCode.BadRequest {
                                description = "Invalid file upload"
                                schema = jsonSchema<ErrorResponse>()
                            }
                            HttpStatusCode.InternalServerError {
                                description = "Internal server error (quite possible)"
                                schema = jsonSchema<ErrorResponse>()
                            }
                        }
                    }

                // RSS post creation
                post("/api/rss/post") { rssModule.createPostRoute(call) }
                    .describe {
                        summary = "Create RSS post"
                        description = "Create a new RSS feed post"
                        documentSecurityRequirements()
                        requestBody {
                            required = true
                            ContentType.Application.Json { schema = jsonSchema<NewPostRequest>() }
                            ContentType.Application.FormUrlEncoded {
                                schema = jsonSchema<NewPostRequest>()
                            }
                        }
                        responses { documentNewPostResponses<NewRssPostResponse>() }
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
                        documentSecurityRequirements()
                        requestBody {
                            required = true
                            ContentType.Application.Json { schema = jsonSchema<NewPostRequest>() }
                            ContentType.Application.FormUrlEncoded {
                                schema = jsonSchema<NewPostRequest>()
                            }
                        }
                        responses { documentNewPostResponses<NewBlueSkyPostResponse>() }
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
                        documentSecurityRequirements()
                        requestBody {
                            required = true
                            ContentType.Application.Json { schema = jsonSchema<NewPostRequest>() }
                            ContentType.Application.FormUrlEncoded {
                                schema = jsonSchema<NewPostRequest>()
                            }
                        }
                        responses { documentNewPostResponses<NewMastodonPostResponse>() }
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
                        documentSecurityRequirements()
                        requestBody {
                            required = true
                            ContentType.Application.Json { schema = jsonSchema<NewPostRequest>() }
                            ContentType.Application.FormUrlEncoded {
                                schema = jsonSchema<NewPostRequest>()
                            }
                        }
                        responses { documentNewPostResponses<NewTwitterPostResponse>() }
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
                        documentSecurityRequirements()
                        requestBody {
                            required = true
                            ContentType.Application.Json { schema = jsonSchema<NewPostRequest>() }
                            ContentType.Application.FormUrlEncoded {
                                schema = jsonSchema<NewPostRequest>()
                            }
                        }
                        responses { documentNewPostResponses<NewLinkedInPostResponse>() }
                    }

                post("/api/multiple/post") { formModule.broadcastPostRoute(call) }
                    .describe {
                        summary = "Broadcast post to multiple platforms"
                        description = "Publish a post to multiple social media platforms at once"
                        documentSecurityRequirements()
                        requestBody {
                            required = true
                            ContentType.Application.Json { schema = jsonSchema<NewPostRequest>() }
                            ContentType.Application.FormUrlEncoded {
                                schema = jsonSchema<NewPostRequest>()
                            }
                        }
                        responses { documentNewPostResponses<Map<String, NewPostResponse>>() }
                    }

                // -----------------------------------------------------------
                // OAuth authorization routes

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
                    .describe {
                        summary = "Initiate Twitter OAuth authorization"
                        documentOAuthAuthorizeSpec("OAuth 1.0a", "Twitter")
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
                    .describe {
                        summary = "Twitter OAuth callback"
                        documentTwitterCallbackSpec()
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
                    .describe {
                        summary = "Check Twitter authorization status"
                        description =
                            "Check if the user has authorized the application to post to Twitter"
                        documentSecurityRequirements()
                        responses { documentOAuthStatusResponses() }
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
                    .describe {
                        summary = "Initiate LinkedIn OAuth authorization"
                        documentOAuthAuthorizeSpec("OAuth 2.0", "LinkedIn")
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
                    .describe {
                        summary = "LinkedIn OAuth callback"
                        documentLinkedInCallbackSpec()
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
                    .describe {
                        summary = "Check LinkedIn authorization status"
                        description =
                            "Check if the user has authorized the application to post to LinkedIn"
                        documentSecurityRequirements()
                        responses { documentOAuthStatusResponses() }
                    }
            }

            // -----------------------------------------------------------
            // Public RSS feed

            get("/rss") { rssModule.generateRssRoute(call) }
                .describe {
                    summary = "Get RSS feed"
                    description =
                        "Generate and retrieve the RSS feed containing all published posts"
                    parameters {
                        query("filterByLinks") {
                            required = false
                            description = "Filter to only include posts with links (true/false)"
                        }
                        query("filterByImages") {
                            required = false
                            description = "Filter to only include posts with images (true/false)"
                        }
                    }
                    responses {
                        HttpStatusCode.OK {
                            description = "RSS feed in XML format"
                            ContentType.Application.Rss()
                        }
                        HttpStatusCode.InternalServerError {
                            description = "Failed to generate RSS feed"
                            schema = jsonSchema<ErrorResponse>()
                        }
                    }
                }

            get("/rss/target/{target}") { rssModule.generateRssRoute(call) }
                .describe {
                    summary = "Get RSS feed for specific target"
                    description =
                        "Generate and retrieve the RSS feed filtered by target platform " +
                            "(e.g., 'mastodon', 'twitter', 'bluesky', 'linkedin')"
                    parameters {
                        path("target") {
                            required = true
                            description =
                                "Target platform to filter posts by (e.g., 'mastodon', 'twitter', 'bluesky', 'linkedin')"
                        }
                        query("filterByLinks") {
                            required = false
                            description = "Filter to only include posts with links (true/false)"
                        }
                        query("filterByImages") {
                            required = false
                            description = "Filter to only include posts with images (true/false)"
                        }
                    }
                    responses {
                        HttpStatusCode.OK {
                            description = "RSS feed in XML format filtered by target"
                            ContentType.Application.Rss()
                        }
                        HttpStatusCode.InternalServerError {
                            description = "Failed to generate RSS feed"
                            schema = jsonSchema<ErrorResponse>()
                        }
                    }
                }

            get("/rss/{uuid}") { rssModule.getRssItem(call) }
                .describe {
                    summary = "Get RSS item by UUID"
                    description = "Retrieve a specific RSS post/item by its UUID"
                    parameters {
                        path("uuid") {
                            required = true
                            description = "UUID of the post to retrieve"
                        }
                    }
                    responses {
                        HttpStatusCode.OK {
                            description = "Post retrieved successfully"
                            schema = jsonSchema<Post>()
                        }
                        HttpStatusCode.BadRequest {
                            description = "Missing UUID parameter"
                            schema = jsonSchema<ErrorResponse>()
                        }
                        HttpStatusCode.NotFound {
                            description = "Post not found"
                            schema = jsonSchema<ErrorResponse>()
                        }
                        HttpStatusCode.InternalServerError {
                            description = "Failed to retrieve post"
                            schema = jsonSchema<ErrorResponse>()
                        }
                    }
                }

            get("/files/{uuid}") { filesModule.getFile(call) }
                .describe {
                    summary = "Get uploaded file"
                    description =
                        "Retrieve an uploaded file by its UUID. Returns the file content with " +
                            "appropriate Content-Type and Content-Disposition headers."
                    parameters {
                        path("uuid") {
                            required = true
                            description = "UUID of the file to retrieve"
                        }
                    }
                    responses {
                        HttpStatusCode.OK {
                            description =
                                "File content (image or other media type). " +
                                    "Content-Type header will match the file's mimetype."
                            ContentType.Application.OctetStream {
                                schema =
                                    JsonSchema(
                                        type = JsonType.STRING,
                                        format = "binary",
                                        description = "Binary file content",
                                    )
                            }
                        }
                        HttpStatusCode.BadRequest {
                            description = "Missing UUID parameter"
                            schema = jsonSchema<ErrorResponse>()
                        }
                        HttpStatusCode.NotFound {
                            description =
                                "File not found in database or file content not found on disk"
                            schema = jsonSchema<ErrorResponse>()
                        }
                        HttpStatusCode.InternalServerError {
                            description = "Failed to retrieve file"
                            schema = jsonSchema<ErrorResponse>()
                        }
                    }
                }

            // Manual static file serving with absolute paths and fallback
            if (config.server.staticContentPaths.isNotEmpty()) {
                get("/{path...}") { staticFilesModule.serveStaticFile(call) }
            }
        }
    }
}
