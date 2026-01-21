package com.alexn.socialpublish

import arrow.continuations.SuspendApp
import arrow.fx.coroutines.resourceScope
import com.alexn.socialpublish.db.Database
import com.alexn.socialpublish.integrations.bluesky.BlueskyConfig
import com.alexn.socialpublish.integrations.mastodon.MastodonConfig
import com.alexn.socialpublish.integrations.twitter.TwitterConfig
import com.alexn.socialpublish.modules.AuthModule
import com.alexn.socialpublish.modules.FilesConfig
import com.alexn.socialpublish.server.ServerAuthConfig
import com.alexn.socialpublish.server.ServerConfig
import com.alexn.socialpublish.server.startServer
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.NoOpCliktCommand
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

private val logger = KotlinLogging.logger {}

/** Main CLI command that delegates to subcommands. */
class SocialPublishCli : NoOpCliktCommand(name = "social-publish") {
    init {
        subcommands(StartServerCommand(), GenBcryptHashCommand())
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
            .file(mustExist = true, canBeDir = true, canBeFile = false, mustBeReadable = true)
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
                    "Password for server authentication - supports BCrypt hashes (env: SERVER_AUTH_PASSWORD)",
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
    private val uploadedFilesPath: String by
        option(
                "--uploaded-files-path",
                help = "Directory where uploaded files are stored (env: UPLOADED_FILES_PATH)",
                envvar = "UPLOADED_FILES_PATH",
            )
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

        val config =
            AppConfig(
                server = serverConfig,
                files = filesConfig,
                bluesky = blueskyConfig,
                mastodon = mastodonConfig,
                twitter = twitterConfig,
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
                    val resources = Database.resourceBundle(config.server.dbPath).bind()

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

/** Subcommand to generate BCrypt hash from a password. */
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

fun main(args: Array<String>) {
    SocialPublishCli().main(args)
}
