package socialpublish.backend

import arrow.continuations.SuspendApp
import arrow.fx.coroutines.resourceScope
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.NoOpCliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.prompt
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.cio.CIO
import java.io.File
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import socialpublish.backend.clients.bluesky.BlueskyConfig
import socialpublish.backend.clients.linkedin.LinkedInConfig
import socialpublish.backend.clients.llm.LlmConfig
import socialpublish.backend.clients.mastodon.MastodonConfig
import socialpublish.backend.clients.twitter.TwitterConfig
import socialpublish.backend.db.CreateResult
import socialpublish.backend.db.Database
import socialpublish.backend.db.DatabaseBundle
import socialpublish.backend.db.UsersDatabase
import socialpublish.backend.modules.AuthModule
import socialpublish.backend.modules.FilesConfig
import socialpublish.backend.server.ServerAuthConfig
import socialpublish.backend.server.ServerConfig
import socialpublish.backend.server.startServer

private val logger = KotlinLogging.logger {}

/** Main CLI command that delegates to subcommands. */
class SocialPublishCli : NoOpCliktCommand(name = "social-publish") {
    init {
        subcommands(
            StartServerCommand(),
            GenBcryptHashCommand(),
            CheckPasswordCommand(),
            CreateUserCommand(),
        )
    }
}

/** Subcommand to start the server. */
class StartServerCommand : CliktCommand(name = "start-server") {
    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Start the Social Publish server"

    // Server configuration
    private val dbPath: String by
        option(
                "--db-path",
                help = "Path to the SQLite database file (env: DB_PATH)",
                envvar = "DB_PATH",
            )
            .required()

    private val httpPort: Int by
        option("--http-port", help = "Port to listen on (env: HTTP_PORT)", envvar = "HTTP_PORT")
            .int()
            .default(3000)

    private val baseUrl: String by
        option(
                "--base-url",
                help = "Public URL of this server (env: BASE_URL)",
                envvar = "BASE_URL",
            )
            .required()

    private val staticContentPaths: List<File> by
        option(
                "--static-content-path",
                help = "Path to serve static content from (default: public)",
                envvar = "STATIC_CONTENT_PATH",
            )
            .file(mustExist = false, canBeDir = true, canBeFile = false)
            .multiple()

    // Server authentication configuration
    private val serverAuthUsername: String by
        option(
                "--server-auth-username",
                help = "Username for server authentication (env: SERVER_AUTH_USERNAME)",
                envvar = "SERVER_AUTH_USERNAME",
            )
            .required()

    private val serverAuthPassword: String by
        option(
                "--server-auth-password",
                help =
                    "Password for server authentication, BCrypt hash (env: SERVER_AUTH_PASSWORD)",
                envvar = "SERVER_AUTH_PASSWORD",
            )
            .required()

    private val serverAuthJwtSecret: String by
        option(
                "--server-auth-jwt-secret",
                help = "JWT secret for server authentication (env: JWT_SECRET)",
                envvar = "JWT_SECRET",
            )
            .required()

    // Files storage configuration
    private val uploadedFilesPath: File by
        option(
                "--uploaded-files-path",
                help = "Directory where uploaded files are stored (env: UPLOADED_FILES_PATH)",
                envvar = "UPLOADED_FILES_PATH",
            )
            .file(mustExist = false, canBeDir = true, canBeFile = false)
            .required()

    // Bluesky integration (optional)
    private val blueskyService: String by
        option(
                "--bluesky-service",
                help = "URL of the Bluesky service (env: BSKY_SERVICE)",
                envvar = "BSKY_SERVICE",
            )
            .default("https://bsky.social")

    private val blueskyUsername: String? by
        option(
            "--bluesky-username",
            help = "Username for Bluesky authentication (env: BSKY_USERNAME)",
            envvar = "BSKY_USERNAME",
        )

    private val blueskyPassword: String? by
        option(
            "--bluesky-password",
            help = "Password for Bluesky authentication (env: BSKY_PASSWORD)",
            envvar = "BSKY_PASSWORD",
        )

    // Mastodon integration (optional)
    private val mastodonHost: String? by
        option(
            "--mastodon-host",
            help = "Mastodon instance host URL (env: MASTODON_HOST)",
            envvar = "MASTODON_HOST",
        )

    private val mastodonAccessToken: String? by
        option(
            "--mastodon-access-token",
            help = "Mastodon API access token (env: MASTODON_ACCESS_TOKEN)",
            envvar = "MASTODON_ACCESS_TOKEN",
        )

    // Twitter integration (optional)
    private val twitterOauth1ConsumerKey: String? by
        option(
            "--twitter-oauth1-consumer-key",
            help = "Twitter OAuth1 consumer key (env: TWITTER_OAUTH1_CONSUMER_KEY)",
            envvar = "TWITTER_OAUTH1_CONSUMER_KEY",
        )

    private val twitterOauth1ConsumerSecret: String? by
        option(
            "--twitter-oauth1-consumer-secret",
            help = "Twitter OAuth1 consumer secret (env: TWITTER_OAUTH1_CONSUMER_SECRET)",
            envvar = "TWITTER_OAUTH1_CONSUMER_SECRET",
        )

    // LinkedIn integration (optional)
    private val linkedinClientId: String? by
        option(
            "--linkedin-client-id",
            help = "LinkedIn OAuth2 client ID (env: LINKEDIN_CLIENT_ID)",
            envvar = "LINKEDIN_CLIENT_ID",
        )

    private val linkedinClientSecret: String? by
        option(
            "--linkedin-client-secret",
            help = "LinkedIn OAuth2 client secret (env: LINKEDIN_CLIENT_SECRET)",
            envvar = "LINKEDIN_CLIENT_SECRET",
        )

    // LLM integration for alt-text generation (optional)
    private val llmApiUrl: String? by
        option(
            "--llm-api-url",
            help =
                "LLM API endpoint URL (e.g., 'https://api.openai.com/v1/chat/completions') (env: LLM_API_URL)",
            envvar = "LLM_API_URL",
        )

    private val llmApiKey: String? by
        option(
            "--llm-api-key",
            help = "API key for LLM provider (env: LLM_API_KEY)",
            envvar = "LLM_API_KEY",
        )

    private val llmModel: String? by
        option(
            "--llm-model",
            help = "LLM model to use (e.g., 'gpt-4o-mini', 'pixtral-12b-2409') (env: LLM_MODEL)",
            envvar = "LLM_MODEL",
        )

    override fun run() {
        val serverAuthConfig =
            ServerAuthConfig(
                username = serverAuthUsername,
                passwordHash = serverAuthPassword,
                jwtSecret = serverAuthJwtSecret,
            )

        val serverConfig =
            ServerConfig(
                dbPath = dbPath,
                httpPort = httpPort,
                baseUrl = baseUrl,
                staticContentPaths = staticContentPaths,
                auth = serverAuthConfig,
            )

        val filesConfig = FilesConfig(uploadedFilesPath = uploadedFilesPath, baseUrl = baseUrl)

        // Build optional integration configs only if credentials are provided
        val blueskyConfig =
            if (blueskyUsername != null && blueskyPassword != null) {
                BlueskyConfig(
                    service = blueskyService,
                    username = blueskyUsername!!,
                    password = blueskyPassword!!,
                )
            } else {
                null
            }

        val mastodonConfig =
            if (mastodonHost != null && mastodonAccessToken != null) {
                MastodonConfig(host = mastodonHost!!, accessToken = mastodonAccessToken!!)
            } else {
                null
            }

        val twitterConfig =
            if (twitterOauth1ConsumerKey != null && twitterOauth1ConsumerSecret != null) {
                TwitterConfig(
                    oauth1ConsumerKey = twitterOauth1ConsumerKey!!,
                    oauth1ConsumerSecret = twitterOauth1ConsumerSecret!!,
                )
            } else {
                null
            }

        val linkedinConfig =
            if (linkedinClientId != null && linkedinClientSecret != null) {
                LinkedInConfig(clientId = linkedinClientId!!, clientSecret = linkedinClientSecret!!)
            } else {
                null
            }

        val llmConfig =
            if (llmApiUrl != null && llmApiKey != null && llmModel != null) {
                LlmConfig(apiUrl = llmApiUrl!!, apiKey = llmApiKey!!, model = llmModel!!)
            } else {
                null
            }

        val config =
            AppConfig(
                server = serverConfig,
                files = filesConfig,
                bluesky = blueskyConfig,
                mastodon = mastodonConfig,
                twitter = twitterConfig,
                linkedin = linkedinConfig,
                llm = llmConfig,
            )

        // SuspendApp currently has issues with System.exit, hence logic above cannot
        // be inside SuspendApp

        SuspendApp {
            resourceScope {
                logger.info { "Starting the Social Publish backend..." }
                logger.info { "Using database path: ${config.server.dbPath}" }
                logger.info {
                    "Serving static content from: ${config.server.staticContentPaths.joinToString(", ")}"
                }
                try {
                    val resources = DatabaseBundle.resource(config.server.dbPath).bind()

                    logger.info { "Database initialized successfully" }
                    val _ =
                        startServer(
                                config,
                                resources.documentsDb,
                                resources.postsDb,
                                resources.filesDb,
                                engine = CIO,
                            )
                            .bind()

                    logger.info { "Server running on port ${config.server.httpPort}" }
                    awaitCancellation()
                } catch (e: Exception) {
                    logger.error(e) { "Application failed to start" }
                    throw e
                }
            }
        }
    }
}

/** Subcommand to generate a BCrypt hash for a password. */
class GenBcryptHashCommand : CliktCommand(name = "gen-bcrypt-hash") {
    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Generate a BCrypt hash for a password"

    private val quiet by
        option("--quiet", "-q", help = "Only output the hash without any messages").flag()

    private val password by
        option("--password", "-p", help = "Password to hash (will prompt if not provided)")
            .prompt("Enter password", hideInput = true, requireConfirmation = false)

    override fun run() {
        val hash = AuthModule.hashPassword(password)

        if (quiet) {
            echo(hash)
        } else {
            echo()
            echo("BCrypt hash:")
            echo(hash)
            echo()
            echo(
                "You can use this hash as the value for SERVER_AUTH_PASSWORD environment variable or --server-auth-password option."
            )
        }
    }
}

/** Subcommand to check a password against the stored BCrypt hash. */
class CheckPasswordCommand : CliktCommand(name = "check-password") {
    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Check if a password matches the stored BCrypt hash"

    private val serverAuthPassword: String by
        option(
                "--server-auth-password",
                help = "BCrypt hash to check against (env: SERVER_AUTH_PASSWORD)",
                envvar = "SERVER_AUTH_PASSWORD",
            )
            .required()

    private val password by
        option("--password", "-p", help = "Password to check (will prompt if not provided)")
            .prompt("Enter password to check", hideInput = true, requireConfirmation = false)

    override fun run() {
        val authModule = AuthModule("dummy")

        // Use reflection to access the private verifyPassword method
        val verifyMethod =
            AuthModule::class
                .java
                .getDeclaredMethod("verifyPassword", String::class.java, String::class.java)
        verifyMethod.isAccessible = true
        val matches =
            verifyMethod.invoke(authModule, password, serverAuthPassword.trim()) as Boolean

        if (matches) {
            echo("✓ Password matches the hash")
        } else {
            echo("✗ Password does NOT match the hash")
            throw ProgramResult(1)
        }
    }
}

/** Subcommand to create a new user. */
class CreateUserCommand : CliktCommand(name = "create-user") {
    override fun help(context: com.github.ajalt.clikt.core.Context) = "Create a new user account"

    private val dbPath: String by
        option(
                "--db-path",
                help = "Path to the SQLite database file (env: DB_PATH)",
                envvar = "DB_PATH",
            )
            .required()

    private val username by
        option("--username", "-u", help = "Username for the new user").prompt("Enter username")

    private val password by
        option("--password", "-p", help = "Password for the new user (will prompt if not provided)")
            .prompt("Enter password", hideInput = true, requireConfirmation = true)

    override fun run() {
        runBlocking {
            resourceScope {
                val db = Database.connect(dbPath).bind()
                val usersDb = UsersDatabase(db)

                when (val result = usersDb.createUser(username = username, password = password)) {
                    is arrow.core.Either.Left -> {
                        echo("Error creating user: ${result.value.message}", err = true)
                        throw ProgramResult(1)
                    }
                    is arrow.core.Either.Right -> {
                        when (val createResult = result.value) {
                            is CreateResult.Created -> {
                                echo()
                                echo("✓ User created successfully!")
                                echo("  Username: ${createResult.value.username}")
                                echo("  User ID:  ${createResult.value.uuid}")
                                echo("  Created:  ${createResult.value.createdAt}")
                                echo()
                            }
                            is CreateResult.Duplicate -> {
                                echo(
                                    "Error creating user: User with username '$username' already exists",
                                    err = true,
                                )
                                throw ProgramResult(1)
                            }
                        }
                    }
                }
            }
        }
    }
}

fun main(args: Array<String>) {
    SocialPublishCli().main(args)
}
