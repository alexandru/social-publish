@file:OptIn(ExperimentalKtorApi::class)

package socialpublish.backend.server

import arrow.continuations.ktor.server
import arrow.core.getOrElse
import arrow.fx.coroutines.resource
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngineFactory
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.utils.io.ExperimentalKtorApi
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.slf4j.event.Level
import socialpublish.backend.AppConfig
import socialpublish.backend.clients.bluesky.BlueskyApiModule
import socialpublish.backend.clients.linkedin.LinkedInApiModule
import socialpublish.backend.clients.llm.GenerateAltTextRequest
import socialpublish.backend.clients.llm.GenerateAltTextResponse
import socialpublish.backend.clients.llm.LlmApiModule
import socialpublish.backend.clients.mastodon.MastodonApiModule
import socialpublish.backend.clients.twitter.TwitterApiModule
import socialpublish.backend.common.*
import socialpublish.backend.db.DocumentsDatabase
import socialpublish.backend.db.FilesDatabase
import socialpublish.backend.db.Post
import socialpublish.backend.db.PostsDatabase
import socialpublish.backend.db.UserSettings
import socialpublish.backend.db.UsersDatabase
import socialpublish.backend.modules.*
import socialpublish.backend.server.routes.AccountSettingsView
import socialpublish.backend.server.routes.AuthRoutes
import socialpublish.backend.server.routes.FeedRoutes
import socialpublish.backend.server.routes.FilesRoutes
import socialpublish.backend.server.routes.LinkedInRoutes
import socialpublish.backend.server.routes.LlmRoutes
import socialpublish.backend.server.routes.LoginRequest
import socialpublish.backend.server.routes.LoginResponse
import socialpublish.backend.server.routes.PublishRoutes
import socialpublish.backend.server.routes.SettingsRoutes
import socialpublish.backend.server.routes.StaticAssetsRoutes
import socialpublish.backend.server.routes.TwitterRoutes
import socialpublish.backend.server.routes.UserResponse
import socialpublish.backend.server.routes.UserSettingsPatch
import socialpublish.backend.server.routes.configureAuth
import socialpublish.backend.server.routes.resolveUserUuid

private val logger = KotlinLogging.logger {}

fun startServer(
    config: AppConfig,
    documentsDb: DocumentsDatabase,
    postsDb: PostsDatabase,
    filesDb: FilesDatabase,
    usersDb: UsersDatabase,
    engine: ApplicationEngineFactory<*, *> = CIO,
) = resource {
    logger.info { "Starting HTTP server on port ${config.server.httpPort}..." }

    val staticAssetsRoutes = StaticAssetsRoutes(config.server)
    val feedModule = FeedModule(config.server.baseUrl, postsDb, filesDb)
    val filesModule = FilesModule.create(config.files, filesDb)

    val authRoutes =
        AuthRoutes(config = config.server.auth, usersDb = usersDb, documentsDb = documentsDb)
    val publishRoutes = PublishRoutes()
    val filesRoutes = FilesRoutes(filesModule)
    val feedRoutes = FeedRoutes(feedModule)
    val settingsRoutes = SettingsRoutes(usersDb = usersDb)

    // Social network modules – instantiated once at startup; per-user config is passed per call.
    val blueskyModule = BlueskyApiModule.resource(filesModule).bind()
    val mastodonModule = MastodonApiModule.resource(filesModule).bind()
    val twitterModule =
        TwitterApiModule.resource(
                baseUrl = config.server.baseUrl,
                documentsDb = documentsDb,
                filesModule = filesModule,
            )
            .bind()
    val linkedInModule =
        LinkedInApiModule.resource(
                baseUrl = config.server.baseUrl,
                documentsDb = documentsDb,
                filesModule = filesModule,
            )
            .bind()
    val llmModule = LlmApiModule.resource(filesModule).bind()
    val twitterRoutes = TwitterRoutes(twitterModule, documentsDb)
    val linkedInRoutes = LinkedInRoutes(linkedInModule, documentsDb)
    val llmRoutes = LlmRoutes(llmModule)

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
            allowMethod(io.ktor.http.HttpMethod.Get)
            allowMethod(io.ktor.http.HttpMethod.Post)
            allowMethod(io.ktor.http.HttpMethod.Patch)
            allowMethod(io.ktor.http.HttpMethod.Options)
        }

        install(ContentNegotiation) { json(serverJson()) }

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
        configureAuth(authRoutes)
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

            // HEAD support for /ping endpoint (used by health check services)
            head("/ping") { call.respondText("", status = HttpStatusCode.OK) }
                .describe {
                    summary = "Health check (HEAD)"
                    description = "HEAD method for health checks, returns only headers without body"
                    responses {
                        HttpStatusCode.OK {
                            description = "Server is healthy"
                            ContentType.Text.Plain()
                        }
                    }
                }

            // Authentication routes
            rateLimit(RateLimitName("login")) {
                post("/api/login") { authRoutes.loginRoute(call) }
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
                // Load the authenticated user's UUID and settings into call attributes once
                // per request, making them available to all route handlers without repeated DB
                // queries.
                install(
                    createRouteScopedPlugin("UserContextPlugin") {
                        onCall { call ->
                            val userUuid = call.resolveUserUuid() ?: return@onCall
                            val settings =
                                usersDb.findByUuid(userUuid).getOrElse { null }?.settings
                                    ?: UserSettings()
                            call.attributes.put(UserUuidKey, userUuid)
                            call.attributes.put(UserSettingsKey, settings)
                        }
                    }
                )

                get("/api/protected") { authRoutes.protectedRoute(call) }
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

                get("/api/account/settings") {
                        val userUuid = call.requireUserUuid() ?: return@get
                        settingsRoutes.getSettingsRoute(userUuid, call)
                    }
                    .describe {
                        summary = "Get account settings"
                        description =
                            "Returns the user's settings. Non-sensitive fields contain real values; sensitive fields (passwords, tokens, keys) contain \"****\" when a value is stored."
                        documentSecurityRequirements()
                        responses {
                            HttpStatusCode.OK {
                                description = "Account settings view"
                                schema = jsonSchema<AccountSettingsView>()
                            }
                            HttpStatusCode.Unauthorized {
                                description = "Not authenticated"
                                schema = jsonSchema<ErrorResponse>()
                            }
                        }
                    }

                patch("/api/account/settings") {
                        val userUuid = call.requireUserUuid() ?: return@patch
                        settingsRoutes.patchSettingsRoute(userUuid, call)
                    }
                    .describe {
                        summary = "Partially update user settings"
                        description =
                            "Partially updates the user's settings. A null section removes that integration. Omit fields to keep existing values unchanged."
                        documentSecurityRequirements()
                        requestBody {
                            required = true
                            ContentType.Application.Json {
                                schema = jsonSchema<UserSettingsPatch>()
                            }
                        }
                        responses {
                            HttpStatusCode.OK {
                                description = "Updated settings view"
                                schema = jsonSchema<AccountSettingsView>()
                            }
                            HttpStatusCode.Unauthorized {
                                description = "Not authenticated"
                                schema = jsonSchema<ErrorResponse>()
                            }
                            HttpStatusCode.BadRequest {
                                description = "Invalid settings body"
                                schema = jsonSchema<ErrorResponse>()
                            }
                        }
                    }

                // File upload
                post("/api/files/upload") {
                        val userUuid = call.requireUserUuid() ?: return@post
                        filesRoutes.uploadFileRoute(userUuid, call)
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
                                                            format = "binary",
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
                                description = "Internal server error"
                                schema = jsonSchema<ErrorResponse>()
                            }
                        }
                    }

                // LLM alt-text generation (extracted to LlmRoutes)
                post("/api/llm/generate-alt-text") {
                        val userUuid = call.requireUserUuid() ?: return@post
                        val llmConfig = call.requireUserSettings(usersDb, userUuid).llm
                        llmRoutes.generateAltTextRoute(userUuid, llmConfig, call)
                    }
                    .describe {
                        summary = "Generate alt-text for image"
                        description =
                            "Generate alt-text for an uploaded image using LLM (OpenAI or Mistral)"
                        documentSecurityRequirements()
                        requestBody {
                            required = true
                            ContentType.Application.Json {
                                schema = jsonSchema<GenerateAltTextRequest>()
                            }
                        }
                        responses {
                            HttpStatusCode.OK {
                                description = "Alt-text generated successfully"
                                schema = jsonSchema<GenerateAltTextResponse>()
                            }
                            HttpStatusCode.Unauthorized {
                                description = "Not authenticated"
                                schema = jsonSchema<ErrorResponse>()
                            }
                            HttpStatusCode.BadRequest {
                                description = "Invalid request"
                                schema = jsonSchema<ErrorResponse>()
                            }
                            HttpStatusCode.NotFound {
                                description = "Image not found"
                                schema = jsonSchema<ErrorResponse>()
                            }
                            HttpStatusCode.ServiceUnavailable {
                                description = "LLM integration not configured"
                                schema = jsonSchema<ErrorResponse>()
                            }
                            HttpStatusCode.InternalServerError {
                                description = "Internal server error"
                                schema = jsonSchema<ErrorResponse>()
                            }
                        }
                    }

                // Feed post creation
                post("/api/feed/post") {
                        val userUuid = call.requireUserUuid() ?: return@post
                        feedRoutes.createPostRoute(userUuid, call)
                    }
                    .describe {
                        summary = "Create feed post"
                        description = "Create a new feed post"
                        documentSecurityRequirements()
                        requestBody {
                            required = true
                            ContentType.Application.Json { schema = jsonSchema<NewPostRequest>() }
                            ContentType.Application.FormUrlEncoded {
                                schema = jsonSchema<NewPostRequest>()
                            }
                        }
                        responses { documentNewPostResponses<NewFeedPostResponse>() }
                    }

                // Social media posts
                post("/api/bluesky/post") {
                        val userUuid = call.requireUserUuid() ?: return@post
                        val blueskyConfig =
                            call.requireUserSettings(usersDb, userUuid).bluesky
                                ?: run {
                                    call.respondWithNotConfigured("Bluesky")
                                    return@post
                                }
                        blueskyModule.createPostRoute(call, blueskyConfig, userUuid)
                    }
                    .describe {
                        summary = "Post to Bluesky"
                        description = "Create a new post on Bluesky"
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
                        val userUuid = call.requireUserUuid() ?: return@post
                        val mastodonConfig =
                            call.requireUserSettings(usersDb, userUuid).mastodon
                                ?: run {
                                    call.respondWithNotConfigured("Mastodon")
                                    return@post
                                }
                        mastodonModule.createPostRoute(call, mastodonConfig, userUuid)
                    }
                    .describe {
                        summary = "Post to Mastodon"
                        description = "Create a new post on Mastodon"
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
                        val userUuid = call.requireUserUuid() ?: return@post
                        val twitterConfig =
                            call.requireUserSettings(usersDb, userUuid).twitter
                                ?: run {
                                    call.respondWithNotConfigured("Twitter")
                                    return@post
                                }
                        twitterRoutes.createPostRoute(userUuid, twitterConfig, call)
                    }
                    .describe {
                        summary = "Post to Twitter/X"
                        description = "Create a new post on Twitter"
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
                        val userUuid = call.requireUserUuid() ?: return@post
                        val linkedInConfig =
                            call.requireUserSettings(usersDb, userUuid).linkedin
                                ?: run {
                                    call.respondWithNotConfigured("LinkedIn")
                                    return@post
                                }
                        linkedInRoutes.createPostRoute(userUuid, linkedInConfig, call)
                    }
                    .describe {
                        summary = "Post to LinkedIn"
                        description = "Create a new post on LinkedIn"
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

                post("/api/multiple/post") {
                        val userUuid = call.requireUserUuid() ?: return@post
                        val userSettings = call.requireUserSettings(usersDb, userUuid)
                        val publishModule =
                            PublishModule(
                                mastodonModule = mastodonModule,
                                mastodonConfig = userSettings.mastodon,
                                blueskyModule = blueskyModule,
                                blueskyConfig = userSettings.bluesky,
                                twitterModule = twitterModule,
                                twitterConfig = userSettings.twitter,
                                linkedInModule = linkedInModule,
                                linkedInConfig = userSettings.linkedin,
                                feedModule = feedModule,
                                userUuid = userUuid,
                            )
                        publishRoutes.broadcastPostRoute(call, publishModule)
                    }
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
                        val userUuid = call.requireUserUuid() ?: return@get
                        val username =
                            call.principal<JWTPrincipal>()?.getClaim("username", String::class)
                                ?: return@get
                        val twitterConfig =
                            call.requireUserSettings(usersDb, userUuid).twitter
                                ?: run {
                                    call.respondWithNotConfigured("Twitter")
                                    return@get
                                }
                        val callbackJwtToken =
                            authRoutes.authModule.generateToken(username, userUuid)
                        twitterRoutes.authorizeRoute(
                            userUuid,
                            twitterConfig,
                            callbackJwtToken,
                            call,
                        )
                    }
                    .describe {
                        summary = "Initiate Twitter OAuth authorization"
                        documentOAuthAuthorizeSpec("OAuth 1.0a", "Twitter")
                    }

                get("/api/twitter/callback") {
                        val userUuid = call.requireUserUuid() ?: return@get
                        val twitterConfig =
                            call.requireUserSettings(usersDb, userUuid).twitter
                                ?: run {
                                    call.respondWithNotConfigured("Twitter")
                                    return@get
                                }
                        twitterRoutes.callbackRoute(userUuid, twitterConfig, call)
                    }
                    .describe {
                        summary = "Twitter OAuth callback"
                        documentTwitterCallbackSpec()
                    }

                get("/api/twitter/status") {
                        val userUuid = call.requireUserUuid() ?: return@get
                        twitterRoutes.statusRoute(userUuid, call)
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
                        val userUuid = call.requireUserUuid() ?: return@get
                        val username =
                            call.principal<JWTPrincipal>()?.getClaim("username", String::class)
                                ?: return@get
                        val linkedInConfig =
                            call.requireUserSettings(usersDb, userUuid).linkedin
                                ?: run {
                                    call.respondWithNotConfigured("LinkedIn")
                                    return@get
                                }
                        val callbackJwtToken =
                            authRoutes.authModule.generateToken(username, userUuid)
                        linkedInRoutes.authorizeRoute(
                            userUuid,
                            linkedInConfig,
                            callbackJwtToken,
                            call,
                        )
                    }
                    .describe {
                        summary = "Initiate LinkedIn OAuth authorization"
                        documentOAuthAuthorizeSpec("OAuth 2.0", "LinkedIn")
                    }

                get("/api/linkedin/callback") {
                        val userUuid = call.requireUserUuid() ?: return@get
                        val linkedInConfig =
                            call.requireUserSettings(usersDb, userUuid).linkedin
                                ?: run {
                                    call.respondWithNotConfigured("LinkedIn")
                                    return@get
                                }
                        linkedInRoutes.callbackRoute(userUuid, linkedInConfig, call)
                    }
                    .describe {
                        summary = "LinkedIn OAuth callback"
                        documentLinkedInCallbackSpec()
                    }

                get("/api/linkedin/status") {
                        val userUuid = call.requireUserUuid() ?: return@get
                        linkedInRoutes.statusRoute(userUuid, call)
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
            // Public feed

            get("/feed/{userUuid}") { feedRoutes.generateFeedRoute(call) }
                .describe {
                    summary = "Get user feed"
                    description = "Generate and retrieve the Atom feed for a specific user"
                    parameters {
                        path("userUuid") {
                            required = true
                            description = "UUID of the user whose feed should be returned"
                        }
                        query("filterByLinks") {
                            required = false
                            description =
                                "Filter posts by links: include (only posts with links) or exclude (only posts without links)"
                        }
                        query("filterByImages") {
                            required = false
                            description =
                                "Filter posts by images: include (only posts with images) or exclude (only posts without images)"
                        }
                    }
                    responses {
                        HttpStatusCode.OK {
                            description = "Atom feed in XML format"
                            ContentType.parse("application/atom+xml")
                        }
                        HttpStatusCode.InternalServerError {
                            description = "Failed to generate feed"
                            schema = jsonSchema<ErrorResponse>()
                        }
                    }
                }

            get("/feed/{userUuid}/target/{target}") { feedRoutes.generateFeedRoute(call) }
                .describe {
                    summary = "Get user feed for specific target"
                    description =
                        "Generate and retrieve a user's feed filtered by target platform " +
                            "(e.g., 'mastodon', 'twitter', 'bluesky', 'linkedin')"
                    parameters {
                        path("userUuid") {
                            required = true
                            description = "UUID of the user whose feed should be returned"
                        }
                        path("target") {
                            required = true
                            description =
                                "Target platform to filter posts by (e.g., 'mastodon', 'twitter', 'bluesky', 'linkedin')"
                        }
                        query("filterByLinks") {
                            required = false
                            description =
                                "Filter posts by links: include (only posts with links) or exclude (only posts without links)"
                        }
                        query("filterByImages") {
                            required = false
                            description =
                                "Filter posts by images: include (only posts with images) or exclude (only posts without images)"
                        }
                    }
                    responses {
                        HttpStatusCode.OK {
                            description = "Atom feed in XML format filtered by target"
                            ContentType.parse("application/atom+xml")
                        }
                        HttpStatusCode.InternalServerError {
                            description = "Failed to generate feed"
                            schema = jsonSchema<ErrorResponse>()
                        }
                    }
                }

            get("/feed/{userUuid}/{uuid}") { feedRoutes.getFeedItem(call) }
                .describe {
                    summary = "Get user feed item by UUID"
                    description = "Retrieve a specific feed item by user UUID and post UUID"
                    parameters {
                        path("userUuid") {
                            required = true
                            description = "UUID of the user who owns the post"
                        }
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

            get("/files/{uuid}") { filesRoutes.getFileRoute(call) }
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
                get("/{path...}") { staticAssetsRoutes.serveStaticFile(call) }
            }
        }
    }
}
