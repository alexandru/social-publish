package com.alexn.socialpublish

import com.alexn.socialpublish.integrations.bluesky.BlueskyConfig
import com.alexn.socialpublish.integrations.mastodon.MastodonConfig
import com.alexn.socialpublish.integrations.twitter.TwitterConfig
import com.alexn.socialpublish.modules.FilesConfig
import com.alexn.socialpublish.server.ServerAuthConfig
import com.alexn.socialpublish.server.ServerConfig
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int

data class AppConfig(
    val server: ServerConfig,
    val files: FilesConfig,
    val bluesky: BlueskyConfig?,
    val mastodon: MastodonConfig?,
    val twitter: TwitterConfig?,
)

class AppCliCommand : CliktCommand(name = "social-publish") {
    // Server configuration
    private val dbPath: String by option("--db-path", help = "Path to the SQLite database file", envvar = "DB_PATH")
        .required()

    private val httpPort: Int by option("--http-port", help = "Port to listen on", envvar = "HTTP_PORT")
        .int()
        .default(3000)

    private val baseUrl: String by option("--base-url", help = "Public URL of this server", envvar = "BASE_URL")
        .required()

    // Server authentication configuration
    private val serverAuthUsername: String by option(
        "--server-auth-username",
        help = "Username for server authentication",
        envvar = "SERVER_AUTH_USERNAME",
    )
        .required()

    private val serverAuthPassword: String by option(
        "--server-auth-password",
        help = "Password for server authentication",
        envvar = "SERVER_AUTH_PASSWORD",
    )
        .required()

    private val serverAuthJwtSecret: String by option(
        "--server-auth-jwt-secret",
        help = "JWT secret for server authentication",
        envvar = "JWT_SECRET",
    )
        .required()

    // Files storage configuration
    private val uploadedFilesPath: String by option(
        "--uploaded-files-path",
        help = "Directory where uploaded files are stored",
        envvar = "UPLOADED_FILES_PATH",
    )
        .required()

    // Bluesky integration (optional)
    private val blueskyService: String by option("--bluesky-service", help = "URL of the Bluesky service", envvar = "BSKY_SERVICE")
        .default("https://bsky.social")

    private val blueskyUsername: String? by option(
        "--bluesky-username",
        help = "Username for Bluesky authentication",
        envvar = "BSKY_USERNAME",
    )

    private val blueskyPassword: String? by option(
        "--bluesky-password",
        help = "Password for Bluesky authentication",
        envvar = "BSKY_PASSWORD",
    )

    // Mastodon integration (optional)
    private val mastodonHost: String? by option("--mastodon-host", help = "Mastodon instance host URL", envvar = "MASTODON_HOST")

    private val mastodonAccessToken: String? by option(
        "--mastodon-access-token",
        help = "Mastodon API access token",
        envvar = "MASTODON_ACCESS_TOKEN",
    )

    // Twitter integration (optional)
    private val twitterOauth1ConsumerKey: String? by option(
        "--twitter-oauth1-consumer-key",
        help = "Twitter OAuth1 consumer key",
        envvar = "TWITTER_OAUTH1_CONSUMER_KEY",
    )

    private val twitterOauth1ConsumerSecret: String? by option(
        "--twitter-oauth1-consumer-secret",
        help = "Twitter OAuth1 consumer secret",
        envvar = "TWITTER_OAUTH1_CONSUMER_SECRET",
    )

    lateinit var config: AppConfig
        private set

    override fun run() {
        val serverAuthConfig =
            ServerAuthConfig(
                username = serverAuthUsername,
                password = serverAuthPassword,
                jwtSecret = serverAuthJwtSecret,
            )

        val serverConfig =
            ServerConfig(
                dbPath = dbPath,
                httpPort = httpPort,
                baseUrl = baseUrl,
                auth = serverAuthConfig,
            )

        val filesConfig =
            FilesConfig(
                uploadedFilesPath = uploadedFilesPath,
                baseUrl = baseUrl,
            )

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
                MastodonConfig(
                    host = mastodonHost!!,
                    accessToken = mastodonAccessToken!!,
                )
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

        config =
            AppConfig(
                server = serverConfig,
                files = filesConfig,
                bluesky = blueskyConfig,
                mastodon = mastodonConfig,
                twitter = twitterConfig,
            )
    }
}
